package chana.jpql

import chana.avro
import chana.avro.Changelog
import chana.avro.RecordFlatView
import chana.avro.UpdateAction
import chana.jpql.nodes._
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.codehaus.jackson.JsonNode

final class JPQLMapperUpdate(meta: JPQLUpdate) extends JPQLEvaluator {

  protected def asToEntity = meta.asToEntity
  protected def asToJoin = meta.asToJoin

  def updateEval(stmt: UpdateStatement, record: GenericRecord): List[List[UpdateAction]] = {
    var toUpdates = List[GenericRecord]()
    if (asToJoin.nonEmpty) {
      val joinField = asToJoin.head._2.tail.head
      val recordFlatView = new RecordFlatView(record.asInstanceOf[GenericRecord], joinField)
      val itr = recordFlatView.iterator
      while (itr.hasNext) {
        val rec = itr.next
        val whereCond = stmt.where.fold(true) { x => whereClause(x, rec) }
        if (whereCond) {
          toUpdates ::= rec
        }
      }
    } else {
      val whereCond = stmt.where.fold(true) { x => whereClause(x, record) }
      if (whereCond) {
        toUpdates ::= record
      }
    }

    toUpdates map { toUpdate =>
      stmt.set.assign :: stmt.set.assigns map {
        case SetAssignClause(target, value) =>
          val v = newValue(value, toUpdate)
          target.path match {
            case Left(path) =>
              val qual = qualIdentVar(path.qual, toUpdate)
              val attrPaths = path.attributes map (x => attribute(x, toUpdate))
              val attrPaths1 = normalizeEntityAttrs(qual, attrPaths, toUpdate.getSchema)
              updateValue(attrPaths1, v, toUpdate)

            case Right(attr) =>
              val fieldName = attribute(attr, toUpdate) // there should be no AS alias
              updateValue(fieldName, v, toUpdate, "/" + attr)
          }
      }
    }
  }

  private def updateValue(attr: String, v: Any, record: GenericRecord, xpath: String): UpdateAction = {
    val field = record.getSchema.getField(attr)
    val value = v match {
      case x: JsonNode => avro.FromJson.fromJsonNode(x, field.schema, false)
      case x           => x
    }

    val prev = record.get(field.pos)
    val rlback = { () => record.put(field.pos, prev) }
    val commit = { () => record.put(field.pos, value) }
    val bytes = avro.avroEncode(value, field.schema).get
    UpdateAction(commit, rlback, Changelog(xpath, value, bytes))
  }

  private def updateValue(attrPaths: List[String], v: Any, record: GenericRecord): UpdateAction = {
    val xpath = new StringBuilder()
    var paths = attrPaths
    var currTarget: Any = record
    var action: UpdateAction = null
    var schema: Schema = record.getSchema

    while (paths.nonEmpty) {
      val path = paths.head
      paths = paths.tail

      schema = schema.getType match {
        case Schema.Type.ARRAY =>
          val elemSchema = avro.getElementType(schema)
          avro.getNonNull(elemSchema.getField(path).schema)
        case Schema.Type.MAP =>
          val valueSchema = avro.getValueType(schema)
          avro.getNonNull(valueSchema.getField(path).schema)
        case Schema.Type.RECORD | Schema.Type.UNION =>
          avro.getNonNull(schema.getField(path).schema)
        case _ => schema
      }

      xpath.append("/").append(path)
      val fieldRec = currTarget match {
        case fieldRec @ avro.FlattenRecord(_, flatField, _, index) if flatField.name == path =>
          xpath.append("[").append(index + 1).append("]")
          fieldRec
        case fieldRec: GenericRecord =>
          fieldRec

        case arr: java.util.Collection[_]             => throw JPQLRuntimeException(currTarget, "is an avro array when fetch its attribute: " + path) // TODO
        case map: java.util.Map[String, _] @unchecked => throw JPQLRuntimeException(currTarget, "is an avro map when fetch its attribute: " + path) // TODO
        case null                                     => throw JPQLRuntimeException(currTarget, "is null when fetch its attribute: " + paths)
        case _                                        => throw JPQLRuntimeException(currTarget, "is not a record when fetch its attribute: " + paths)
      }

      if (paths.isEmpty) { // reaches last path
        action = updateValue(path, v, fieldRec, xpath.toString)
      } else {
        currTarget = fieldRec.get(path)
      }

    }

    action
  }
}
