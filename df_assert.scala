import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.functions._


// -----------------------------------------------------------------------------
// Float comparison strategies
// -----------------------------------------------------------------------------

sealed trait FloatAssert {
  def isEqual(actual: Double, expected: Double): Boolean
}


/**
 * Values are equal if they are equal after rounding to the specified number
 * of decimal places.
 */
case class Rounded(decimalPlaces: Int) extends FloatAssert {

  require(decimalPlaces >= 0, "decimalPlaces must be >= 0")

  override def isEqual(actual: Double, expected: Double): Boolean = {
    val act =
      BigDecimal.decimal(actual)
        .setScale(decimalPlaces, BigDecimal.RoundingMode.HALF_UP)

    val exp =
      BigDecimal.decimal(expected)
        .setScale(decimalPlaces, BigDecimal.RoundingMode.HALF_UP)

    act == exp
  }
}


/**
 * Values are equal if they are equal after truncation to the specified number
 * of decimal places.
 */
case class Truncated(decimalPlaces: Int) extends FloatAssert {

  require(decimalPlaces >= 0, "decimalPlaces must be >= 0")

  override def isEqual(actual: Double, expected: Double): Boolean = {
    val act =
      BigDecimal.decimal(actual)
        .setScale(decimalPlaces, BigDecimal.RoundingMode.DOWN)

    val exp =
      BigDecimal.decimal(expected)
        .setScale(decimalPlaces, BigDecimal.RoundingMode.DOWN)

    act == exp
  }
}


/**
 * Values are equal if their absolute difference does not exceed maxDifference.
 */
case class AbsoluteDiff(maxDifference: Double) extends FloatAssert {

  require(maxDifference >= 0.0, "maxDifference must be >= 0")

  override def isEqual(actual: Double, expected: Double): Boolean =
    math.abs(actual - expected) <= maxDifference
}


/**
 * Values are equal if the difference from expected does not exceed maxPercent.
 *
 * maxPercent is expressed in percent, e.g. 0.001 means 0.001%.
 */
case class PercentDiff(maxPercent: Double) extends FloatAssert {

  require(maxPercent >= 0.0, "maxPercent must be >= 0")

  override def isEqual(actual: Double, expected: Double): Boolean = {
    if (expected == 0.0) {
      actual == 0.0
    } else {
      val diffPercent =
        math.abs(actual - expected) / math.abs(expected) * 100.0

      diffPercent <= maxPercent
    }
  }
}


/**
 * Values are equal if their difference satisfies either the absolute or
 * relative tolerance.
 *
 * relativeTolerance is a ratio, not a percentage.
 */
case class Tolerance(
    absoluteTolerance: Double,
    relativeTolerance: Double
) extends FloatAssert {

  require(absoluteTolerance >= 0.0, "absoluteTolerance must be >= 0")
  require(relativeTolerance >= 0.0, "relativeTolerance must be >= 0")

  override def isEqual(actual: Double, expected: Double): Boolean = {
    val difference =
      math.abs(actual - expected)

    val allowedDifference =
      math.max(
        absoluteTolerance,
        relativeTolerance * math.abs(expected)
      )

    difference <= allowedDifference
  }
}


// -----------------------------------------------------------------------------
// DataFrame assertions
// -----------------------------------------------------------------------------

/**
 * Compares two DataFrames row by row after sorting them by the specified columns.
 *
 * Exact-match columns are compared using strict equality, while Float/Double
 * columns are compared using the supplied FloatAssert strategy.
 *
 * This assertion is fail-fast: it stops at the first mismatch in sorted row
 * order. It is primarily intended for debugging, where the first difference
 * between two consistently sorted DataFrames is usually the most relevant one.
 *
 * Only columns explicitly provided through sortCols, exactMatchCols and
 * floatValueCols are included in schema validation.
 *
 * @param actDf actual DataFrame
 * @param expDf expected DataFrame
 * @param sortCols columns used to sort both DataFrames before comparison
 * @param exactMatchCols columns compared using exact equality
 * @param floatValueCols Float/Double columns compared using floatAssert
 * @param floatAssert comparison strategy used for all Float/Double columns
 */
def assertSortedDataFramesEqual(
    actDf: DataFrame,
    expDf: DataFrame,
    sortCols: Seq[String],
    exactMatchCols: Seq[String],
    floatValueCols: Seq[String],
    floatAssert: FloatAssert
): Unit = {

  require(sortCols.nonEmpty, "sortCols must not be empty")

  val relevantCols =
    (sortCols ++ exactMatchCols ++ floatValueCols).distinct

  assertColumnSchemasEqual(
    actDf,
    expDf,
    relevantCols
  )

  val actSorted: Array[Row] =
    actDf
      .orderBy(sortCols.map(col): _*)
      .collect()

  val expSorted: Array[Row] =
    expDf
      .orderBy(sortCols.map(col): _*)
      .collect()

  // Fail on different row counts
  assert(
    actSorted.length == expSorted.length,
    s"""
       |Different row count:
       |Actual:   ${actSorted.length}
       |Expected: ${expSorted.length}
       |""".stripMargin
  )

  actSorted
    .zip(expSorted)
    .zipWithIndex
    .foreach {
      case ((actRow, expRow), rowIndex) =>

        // Exact value comparison
        exactMatchCols.foreach { column =>
          val actValue =
            actRow.getAs[Any](column)

          val expValue =
            expRow.getAs[Any](column)

          assert(
            actValue == expValue,
            s"""
               |Exact value mismatch:
               |Row:      $rowIndex
               |Column:   $column
               |Actual:   $actValue
               |Expected: $expValue
               |""".stripMargin
          )
        }

        // Float/Double comparison
        floatValueCols.foreach { column =>
          assertFloatValueEquals(
            actValue = actRow.getAs[Any](column),
            expValue = expRow.getAs[Any](column),
            column = column,
            context = s"row=$rowIndex",
            floatAssert = floatAssert
          )
        }
    }
}


/**
 * Compares two DataFrames by matching rows on the specified business key.
 *
 * A full outer join is used to identify rows present only in actual, rows
 * missing from actual, and value mismatches between successfully matched rows.
 *
 * Exact-match columns are compared using null-safe equality, while Float/Double
 * columns are compared using the supplied FloatAssert strategy.
 *
 * Unlike assertSortedDataFramesEqual, this method collects detected differences
 * and reports them together rather than failing on the first value mismatch.
 *
 * Business keys must be non-null and unique within each DataFrame.
 *
 * Only columns explicitly provided through businessKeyCols, exactMatchCols and
 * floatValueCols are included in schema validation.
 *
 * @param actDf actual DataFrame
 * @param expDf expected DataFrame
 * @param businessKeyCols columns used to match actual and expected rows
 * @param exactMatchCols columns compared using exact equality
 * @param floatValueCols Float/Double columns compared using floatAssert
 * @param floatAssert comparison strategy used for all Float/Double columns
 */
def assertDataFramesEqualByBusinessKey(
    actDf: DataFrame,
    expDf: DataFrame,
    businessKeyCols: Seq[String],
    exactMatchCols: Seq[String],
    floatValueCols: Seq[String],
    floatAssert: FloatAssert
): Unit = {

  require(
    businessKeyCols.nonEmpty,
    "businessKeyCols must not be empty"
  )

  val relevantCols =
    (businessKeyCols ++ exactMatchCols ++ floatValueCols).distinct

  assertColumnSchemasEqual(
    actDf,
    expDf,
    relevantCols
  )

  assertBusinessKeyNotNull(
    actDf,
    businessKeyCols,
    "Actual DataFrame"
  )

  assertBusinessKeyNotNull(
    expDf,
    businessKeyCols,
    "Expected DataFrame"
  )

  assertBusinessKeyUnique(
    actDf,
    businessKeyCols,
    "Actual DataFrame"
  )

  assertBusinessKeyUnique(
    expDf,
    businessKeyCols,
    "Expected DataFrame"
  )

  val act =
    actDf.alias("act")

  val exp =
    expDf.alias("exp")

  // Match rows by business key
  val joinCondition: Column =
    businessKeyCols
      .map(column =>
        col(s"act.$column") <=> col(s"exp.$column")
      )
      .reduce(_ && _)

  val joined =
    act.join(
      exp,
      joinCondition,
      "full_outer"
    )

  // Identify unmatched rows
  val actMissing: Column =
    businessKeyCols
      .map(column =>
        col(s"act.$column").isNull
      )
      .reduce(_ && _)

  val expMissing: Column =
    businessKeyCols
      .map(column =>
        col(s"exp.$column").isNull
      )
      .reduce(_ && _)

  val extraRowsInActual =
    joined.filter(
      expMissing && !actMissing
    )

  val missingRowsInActual =
    joined.filter(
      actMissing && !expMissing
    )

  val matchedRows =
    joined.filter(
      !actMissing && !expMissing
    )

  val errors =
    scala.collection.mutable.ArrayBuffer.empty[String]

  val extraInActualCount =
    extraRowsInActual.count()

  val missingInActualCount =
    missingRowsInActual.count()

  if (extraInActualCount > 0) {
    errors +=
      s"Extra rows in actual DataFrame: $extraInActualCount"
  }

  if (missingInActualCount > 0) {
    errors +=
      s"Missing rows in actual DataFrame: $missingInActualCount"
  }

  // Compare exact-match columns
  exactMatchCols.foreach { column =>

    val mismatchCondition =
      !(col(s"act.$column") <=> col(s"exp.$column"))

    val mismatchCount =
      matchedRows
        .filter(mismatchCondition)
        .count()

    if (mismatchCount > 0) {
      errors +=
        s"Exact-match column '$column': $mismatchCount mismatches"
    }
  }

  // Compare Float/Double columns
  floatValueCols.foreach { column =>

    val mismatchCount =
      countFloatColumnMismatches(
        df = matchedRows,
        actColumnName = column,
        expColumnName = column,
        floatAssert = floatAssert
      )

    if (mismatchCount > 0) {
      errors +=
        s"Float column '$column': $mismatchCount mismatches using $floatAssert"
    }
  }

  // Report all detected differences
  assert(
    errors.isEmpty,
    errors.mkString(
      "DataFrame comparison failed:\n  - ",
      "\n  - ",
      ""
    )
  )
}


// -----------------------------------------------------------------------------
// Schema validation
// -----------------------------------------------------------------------------

/**
 * Validates that the specified columns exist in both DataFrames and have
 * matching data types.
 *
 * Additional columns present in either DataFrame are ignored.
 *
 * @param actDf actual DataFrame
 * @param expDf expected DataFrame
 * @param columnNames columns included in schema validation
 */
private def assertColumnSchemasEqual(
    actDf: DataFrame,
    expDf: DataFrame,
    columnNames: Seq[String]
): Unit = {

  val actSchema =
    actDf.schema

  val expSchema =
    expDf.schema

  columnNames.distinct.foreach { column =>

    val actField =
      actSchema.find(_.name == column)

    val expField =
      expSchema.find(_.name == column)

    assert(
      actField.isDefined,
      s"Column '$column' is missing in actual DataFrame"
    )

    assert(
      expField.isDefined,
      s"Column '$column' is missing in expected DataFrame"
    )

    assert(
      actField.get.dataType == expField.get.dataType,
      s"""
         |Data type mismatch for column '$column':
         |Actual:   ${actField.get.dataType}
         |Expected: ${expField.get.dataType}
         |""".stripMargin
    )
  }
}


// -----------------------------------------------------------------------------
// Business key validation
// -----------------------------------------------------------------------------

/**
 * Validates that no business key column contains NULL.
 */
private def assertBusinessKeyNotNull(
    df: DataFrame,
    businessKeyCols: Seq[String],
    dfName: String
): Unit = {

  val containsNullKey =
    businessKeyCols
      .map(col(_).isNull)
      .reduce(_ || _)

  val nullKeyCount =
    df
      .filter(containsNullKey)
      .count()

  assert(
    nullKeyCount == 0,
    s"$dfName contains $nullKeyCount rows with NULL in business key"
  )
}


/**
 * Validates uniqueness of the business key.
 */
private def assertBusinessKeyUnique(
    df: DataFrame,
    businessKeyCols: Seq[String],
    dfName: String
): Unit = {

  val duplicateKeyCount =
    df
      .groupBy(
        businessKeyCols.map(col): _*
      )
      .count()
      .filter(
        col("count") > 1
      )
      .count()

  assert(
    duplicateKeyCount == 0,
    s"$dfName contains $duplicateKeyCount duplicate business keys"
  )
}


// -----------------------------------------------------------------------------
// Float comparison
// -----------------------------------------------------------------------------

/**
 * Compares two Float/Double values using the supplied strategy.
 */
private def assertFloatValueEquals(
    actValue: Any,
    expValue: Any,
    column: String,
    context: String,
    floatAssert: FloatAssert
): Unit = {

  val equal =
    (actValue, expValue) match {

      case (null, null) =>
        true

      case (null, _) | (_, null) =>
        false

      case (act: Number, exp: Number) =>
        floatAssert.isEqual(
          act.doubleValue(),
          exp.doubleValue()
        )

      case _ =>
        throw new IllegalArgumentException(
          s"Column '$column' contains non-numeric values: " +
            s"actual=$actValue, expected=$expValue"
        )
    }

  assert(
    equal,
    s"""
       |Float value mismatch:
       |Context:  $context
       |Column:   $column
       |Actual:   $actValue
       |Expected: $expValue
       |Strategy: $floatAssert
       |""".stripMargin
  )
}


/**
 * Counts Float/Double mismatches for matched business-key rows.
 */
private def countFloatColumnMismatches(
    df: DataFrame,
    actColumnName: String,
    expColumnName: String,
    floatAssert: FloatAssert
): Long = {

  df
    .select(
      col(s"act.$actColumnName").alias("actual"),
      col(s"exp.$expColumnName").alias("expected")
    )
    .collect()
    .count { row =>

      val actValue =
        row.getAs[Any]("actual")

      val expValue =
        row.getAs[Any]("expected")

      (actValue, expValue) match {

        case (null, null) =>
          false

        case (null, _) | (_, null) =>
          true

        case (act: Number, exp: Number) =>
          !floatAssert.isEqual(
            act.doubleValue(),
            exp.doubleValue()
          )

        case _ =>
          throw new IllegalArgumentException(
            s"Non-numeric values encountered: " +
              s"actual=$actValue, expected=$expValue"
          )
      }
    }
    .toLong
}
