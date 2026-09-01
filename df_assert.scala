import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.functions._

import scala.collection.mutable.ArrayBuffer


// =============================================================================
// Float comparison strategies
// =============================================================================

sealed trait FloatComparison {
  def isEqual(actual: Double, expected: Double): Boolean
}


object FloatComparison {

  /**
   * Values are equal if they are equal after rounding to the specified number
   * of decimal places.
   */
  case class Rounded(decimalPlaces: Int) extends FloatComparison {

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
  case class Truncated(decimalPlaces: Int) extends FloatComparison {

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
  case class AbsoluteDiff(maxDifference: Double) extends FloatComparison {

    require(maxDifference >= 0.0, "maxDifference must be >= 0")

    override def isEqual(actual: Double, expected: Double): Boolean =
      math.abs(actual - expected) <= maxDifference
  }


  /**
   * Values are equal if the difference from expected does not exceed maxPercent.
   *
   * maxPercent is expressed in percent, e.g. 0.001 means 0.001%.
   */
  case class PercentDiff(maxPercent: Double) extends FloatComparison {

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
   *
   * Example:
   *   relativeTolerance = 1e-6
   * means approximately 0.0001%.
   */
  case class Tolerance(
      absoluteTolerance: Double,
      relativeTolerance: Double
  ) extends FloatComparison {

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
}


// =============================================================================
// DataFrame assertions
// =============================================================================

object DataFrameAssertions {

  private val MaxReportedMismatches = 20

  private val ReportWidth = 80

  private val FullSeparator =
    "+" + ("=" * (ReportWidth - 2)) + "+"

  private val SectionSeparator =
    "+" + ("-" * (ReportWidth - 2)) + "+"


  // ===========================================================================
  // Public API
  // ===========================================================================

  /**
   * Compares two DataFrames row by row after sorting them by the specified
   * columns.
   *
   * Comparison is performed in the following order:
   *
   * 1. Sorting and row alignment
   *    Both DataFrames are sorted using sortCols and their row counts are
   *    compared.
   *
   * 2. Exact-match comparison
   *    Rows are compared using exactMatchCols.
   *
   * 3. Float/Double comparison
   *    Float/Double columns are compared using the FloatComparison strategy
   *    configured for each column.
   *
   * This assertion is always fail-fast. It stops at the first mismatching row.
   * When a mismatch is found, the actual and expected rows are printed in a
   * CSV-like format to simplify copy/paste and grep-based debugging.
   *
   * Only explicitly configured columns are included in schema validation.
   *
   * @param actDf actual DataFrame
   * @param expDf expected DataFrame
   * @param sortCols columns used to sort both DataFrames before comparison
   * @param exactMatchCols columns compared using exact equality
   * @param floatComparisons Float/Double columns mapped to comparison strategies
   */
  def assertSortedDataFramesEqual(
      actDf: DataFrame,
      expDf: DataFrame,
      sortCols: Seq[String],
      exactMatchCols: Seq[String],
      floatComparisons: Map[String, FloatComparison]
  ): Unit = {

    require(sortCols.nonEmpty, "sortCols must not be empty")

    val relevantCols =
      (sortCols ++ exactMatchCols ++ floatComparisons.keys).distinct

    assertNoColumnOverlap(
      sortCols = sortCols,
      businessKeyCols = Seq.empty,
      exactMatchCols = exactMatchCols,
      floatComparisons = floatComparisons
    )

    assertColumnSchemasEqual(
      actDf,
      expDf,
      relevantCols
    )

    val configuration =
      Seq(
        "Assertion" -> "Sorted DataFrame assertion",
        "Execution mode" -> "Fail-fast",
        "Sorting columns" -> formatSeq(sortCols),
        "Exact-match columns" -> formatSeq(exactMatchCols),
        "Float comparisons" -> formatFloatComparisons(floatComparisons)
      )

    val sections =
      ArrayBuffer.empty[ReportSection]

    println("Sorting and row alignment...")

    val actSorted =
      actDf
        .select(relevantCols.map(col): _*)
        .orderBy(sortCols.map(col): _*)
        .collect()

    val expSorted =
      expDf
        .select(relevantCols.map(col): _*)
        .orderBy(sortCols.map(col): _*)
        .collect()

    if (actSorted.length != expSorted.length) {

      sections += ReportSection(
        title = "Sorting and row alignment",
        status = Failed,
        lines = Seq(
          s"Actual rows:   ${actSorted.length}",
          s"Expected rows: ${expSorted.length}"
        )
      )

      sections += notExecutedSection("Exact-match comparison")
      sections += notExecutedSection("Float/Double comparison")

      val report =
        DataFrameAssertionReport(
          configuration = configuration,
          sections = sections.toSeq
        )

      printReport(report)

      assert(
        assertion = false,
        clue = "Different row count"
      )
    }

    sections += ReportSection(
      title = "Sorting and row alignment",
      status = Passed,
      lines = Seq(
        s"Actual rows:   ${actSorted.length}",
        s"Expected rows: ${expSorted.length}"
      )
    )

    println("Sorting and row alignment... OK")
    println("Exact-match comparison...")

    var exactFailure: Option[(Int, Row, Row)] = None

    var rowIndex = 0

    while (rowIndex < actSorted.length && exactFailure.isEmpty) {

      val actRow =
        actSorted(rowIndex)

      val expRow =
        expSorted(rowIndex)

      val mismatch =
        exactMatchCols.exists { column =>
          actRow.getAs[Any](column) != expRow.getAs[Any](column)
        }

      if (mismatch) {
        exactFailure =
          Some((rowIndex, actRow, expRow))
      }

      rowIndex += 1
    }

    exactFailure match {

      case Some((index, actRow, expRow)) =>

        sections += ReportSection(
          title = "Exact-match comparison",
          status = Failed,
          lines =
            Seq(
              s"First mismatching row index: $index",
              ""
            ) ++
              formatRowDiff(
                actRow = actRow,
                expRow = expRow,
                columns = relevantCols
              )
        )

        sections += notExecutedSection("Float/Double comparison")

        val report =
          DataFrameAssertionReport(
            configuration = configuration,
            sections = sections.toSeq
          )

        printReport(report)

        assert(
          assertion = false,
          clue = s"Exact-match comparison failed at row $index"
        )

      case None =>

        sections += ReportSection(
          title = "Exact-match comparison",
          status = Passed,
          lines = Seq(
            s"Rows checked: ${actSorted.length}",
            s"Columns checked: ${formatSeq(exactMatchCols)}"
          )
        )
    }

    println("Exact-match comparison... OK")
    println("Float/Double comparison...")

    var floatFailure: Option[(Int, Row, Row)] = None

    rowIndex = 0

    while (rowIndex < actSorted.length && floatFailure.isEmpty) {

      val actRow =
        actSorted(rowIndex)

      val expRow =
        expSorted(rowIndex)

      val mismatch =
        floatComparisons.exists {
          case (column, comparison) =>
            !floatValuesEqual(
              actValue = actRow.getAs[Any](column),
              expValue = expRow.getAs[Any](column),
              comparison = comparison
            )
        }

      if (mismatch) {
        floatFailure =
          Some((rowIndex, actRow, expRow))
      }

      rowIndex += 1
    }

    floatFailure match {

      case Some((index, actRow, expRow)) =>

        sections += ReportSection(
          title = "Float/Double comparison",
          status = Failed,
          lines =
            Seq(
              s"First mismatching row index: $index",
              "",
              "Float comparisons:",
              formatFloatComparisonsMultiline(floatComparisons),
              ""
            ) ++
              formatRowDiff(
                actRow = actRow,
                expRow = expRow,
                columns = relevantCols
              )
        )

        val report =
          DataFrameAssertionReport(
            configuration = configuration,
            sections = sections.toSeq
          )

        printReport(report)

        assert(
          assertion = false,
          clue = s"Float/Double comparison failed at row $index"
        )

      case None =>

        sections += ReportSection(
          title = "Float/Double comparison",
          status = Passed,
          lines = Seq(
            s"Rows checked: ${actSorted.length}",
            "Float comparisons:",
            formatFloatComparisonsMultiline(floatComparisons)
          )
        )
    }

    println("Float/Double comparison... OK")

    val report =
      DataFrameAssertionReport(
        configuration = configuration,
        sections = sections.toSeq
      )

    printReport(report)
  }


  /**
   * Compares two DataFrames by matching rows on the specified business key.
   *
   * Comparison is performed in three consecutive stages:
   *
   * 1. Business-key matching
   *    Rows are matched using businessKeyCols. Rows present only in actual are
   *    reported as extra rows, while rows present only in expected are reported
   *    as missing rows.
   *
   * 2. Exact-match comparison
   *    Successfully matched rows are compared using exactMatchCols.
   *
   * 3. Float/Double comparison
   *    Successfully matched rows are compared using the FloatComparison strategy
   *    configured for each Float/Double column.
   *
   * The stages are always executed in this order. Float comparisons are therefore
   * performed only after business-key matching and exact-match comparison.
   *
   * When failFast = false, all three stages are evaluated. The final report
   * contains mismatch counts for all configured comparisons and up to
   * MaxReportedMismatches example rows for failed comparisons.
   *
   * When failFast = true, execution stops after the first failed stage. The
   * report contains all successfully completed preceding stages and a detailed
   * CSV-like diff for the first mismatching row in the failing stage. Remaining
   * stages are reported as NOT EXECUTED.
   *
   * Business-key columns define row identity and are therefore intentionally
   * separate from exactMatchCols, even though both use exact equality.
   *
   * Business keys must be non-null and unique within each DataFrame.
   *
   * A final test report containing the assertion configuration and execution
   * results is always printed.
   *
   * @param actDf actual DataFrame
   * @param expDf expected DataFrame
   * @param businessKeyCols columns used to match actual and expected rows
   * @param exactMatchCols columns compared using exact equality after row matching
   * @param floatComparisons Float/Double columns mapped to comparison strategies
   * @param failFast if true, stops after the first failed comparison stage and
   *                 reports the first mismatching row in detail
   */
  def assertDataFramesEqualByBusinessKey(
      actDf: DataFrame,
      expDf: DataFrame,
      businessKeyCols: Seq[String],
      exactMatchCols: Seq[String],
      floatComparisons: Map[String, FloatComparison],
      failFast: Boolean = false
  ): Unit = {

    require(
      businessKeyCols.nonEmpty,
      "businessKeyCols must not be empty"
    )

    assertNoColumnOverlap(
      sortCols = Seq.empty,
      businessKeyCols = businessKeyCols,
      exactMatchCols = exactMatchCols,
      floatComparisons = floatComparisons
    )

    val relevantCols =
      (
        businessKeyCols ++
          exactMatchCols ++
          floatComparisons.keys
      ).distinct

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

    val configuration =
      Seq(
        "Assertion" -> "Business-key DataFrame assertion",
        "Execution mode" -> (if (failFast) "Fail-fast" else "Full summary"),
        "Business keys" -> formatSeq(businessKeyCols),
        "Exact-match columns" -> formatSeq(exactMatchCols),
        "Float comparisons" -> formatFloatComparisons(floatComparisons),
        "Fail fast" -> failFast.toString
      )

    val sections =
      ArrayBuffer.empty[ReportSection]

    val act =
      actDf
        .select(relevantCols.map(col): _*)
        .alias("act")

    val exp =
      expDf
        .select(relevantCols.map(col): _*)
        .alias("exp")

    val joinCondition =
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

    val actMissing =
      businessKeyCols
        .map(column => col(s"act.$column").isNull)
        .reduce(_ && _)

    val expMissing =
      businessKeyCols
        .map(column => col(s"exp.$column").isNull)
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

    // -------------------------------------------------------------------------
    // Business-key matching
    // -------------------------------------------------------------------------

    println("Business-key matching...")

    val extraInActualCount =
      extraRowsInActual.count()

    val missingInActualCount =
      missingRowsInActual.count()

    val matchedRowCount =
      matchedRows.count()

    val businessKeyFailed =
      extraInActualCount > 0 ||
        missingInActualCount > 0

    if (businessKeyFailed) {

      if (failFast) {

        val firstExtra =
          extraRowsInActual
            .orderBy(
              businessKeyCols.map(c => col(s"act.$c")): _*
            )
            .limit(1)
            .collect()
            .headOption

        val firstMissing =
          missingRowsInActual
            .orderBy(
              businessKeyCols.map(c => col(s"exp.$c")): _*
            )
            .limit(1)
            .collect()
            .headOption

        val detailLines =
          firstExtra match {

            case Some(row) =>
              Seq(
                "First unmatched row:",
                "Location: ACTUAL only",
                ""
              ) ++
                formatJoinedSingleRow(
                  row = row,
                  sourceAlias = "act",
                  sourceLabel = "ACTUAL",
                  columns = relevantCols
                )

            case None =>
              firstMissing match {

                case Some(row) =>
                  Seq(
                    "First unmatched row:",
                    "Location: EXPECTED only",
                    ""
                  ) ++
                    formatJoinedSingleRow(
                      row = row,
                      sourceAlias = "exp",
                      sourceLabel = "EXPECTED",
                      columns = relevantCols
                    )

                case None =>
                  Seq.empty
              }
          }

        sections += ReportSection(
          title = "Business-key matching",
          status = Failed,
          lines =
            Seq(
              s"Matched rows:            $matchedRowCount",
              s"Extra rows in actual:    $extraInActualCount",
              s"Missing rows in actual:  $missingInActualCount",
              ""
            ) ++ detailLines
        )

        sections += notExecutedSection("Exact-match comparison")
        sections += notExecutedSection("Float/Double comparison")

        val report =
          DataFrameAssertionReport(
            configuration = configuration,
            sections = sections.toSeq
          )

        printReport(report)

        assert(
          assertion = false,
          clue = "Business-key matching failed"
        )
      }

      val lines =
        ArrayBuffer[String](
          s"Matched rows:            $matchedRowCount",
          s"Extra rows in actual:    $extraInActualCount",
          s"Missing rows in actual:  $missingInActualCount"
        )

      if (extraInActualCount > 0) {
        lines += ""
        lines += s"First ${math.min(extraInActualCount, MaxReportedMismatches)} extra rows in actual:"
        lines ++=
          formatJoinedRows(
            df = extraRowsInActual,
            sourceAlias = "act",
            sourceLabel = "ACTUAL",
            columns = relevantCols,
            limit = MaxReportedMismatches
          )

        if (extraInActualCount > MaxReportedMismatches) {
          lines +=
            s"... ${extraInActualCount - MaxReportedMismatches} additional rows not shown"
        }
      }

      if (missingInActualCount > 0) {
        lines += ""
        lines += s"First ${math.min(missingInActualCount, MaxReportedMismatches)} missing rows in actual:"
        lines ++=
          formatJoinedRows(
            df = missingRowsInActual,
            sourceAlias = "exp",
            sourceLabel = "EXPECTED",
            columns = relevantCols,
            limit = MaxReportedMismatches
          )

        if (missingInActualCount > MaxReportedMismatches) {
          lines +=
            s"... ${missingInActualCount - MaxReportedMismatches} additional rows not shown"
        }
      }

      sections += ReportSection(
        title = "Business-key matching",
        status = Failed,
        lines = lines.toSeq
      )

      println("Business-key matching... FAILED")
    } else {

      sections += ReportSection(
        title = "Business-key matching",
        status = Passed,
        lines = Seq(
          s"Matched rows:            $matchedRowCount",
          s"Extra rows in actual:    0",
          s"Missing rows in actual:  0"
        )
      )

      println("Business-key matching... OK")
    }

    // -------------------------------------------------------------------------
    // Exact-match comparison
    // -------------------------------------------------------------------------

    println("Exact-match comparison...")

    if (failFast) {

      val exactMismatchCondition =
        exactMatchCols
          .map(column =>
            !(col(s"act.$column") <=> col(s"exp.$column"))
          )
          .reduceOption(_ || _)
          .getOrElse(lit(false))

      val firstMismatch =
        matchedRows
          .filter(exactMismatchCondition)
          .orderBy(
            businessKeyCols.map(c => col(s"act.$c")): _*
          )
          .limit(1)
          .collect()
          .headOption

      firstMismatch match {

        case Some(row) =>

          sections += ReportSection(
            title = "Exact-match comparison",
            status = Failed,
            lines =
              Seq(
                "First mismatching row:",
                ""
              ) ++
                formatJoinedRowDiff(
                  row = row,
                  columns = relevantCols
                )
          )

          sections += notExecutedSection("Float/Double comparison")

          val report =
            DataFrameAssertionReport(
              configuration = configuration,
              sections = sections.toSeq
            )

          printReport(report)

          assert(
            assertion = false,
            clue = "Exact-match comparison failed"
          )

        case None =>

          sections += ReportSection(
            title = "Exact-match comparison",
            status = Passed,
            lines = Seq(
              s"Rows checked: $matchedRowCount",
              s"Columns checked: ${formatSeq(exactMatchCols)}"
            )
          )

          println("Exact-match comparison... OK")
      }
    } else {

      val exactLines =
        ArrayBuffer[String](
          s"Rows checked: $matchedRowCount"
        )

      var exactFailed =
        false

      exactMatchCols.foreach { column =>

        val mismatches =
          matchedRows.filter(
            !(col(s"act.$column") <=> col(s"exp.$column"))
          )

        val mismatchCount =
          mismatches.count()

        if (mismatchCount > 0) {
          exactFailed = true
        }

        exactLines +=
          s"$column: $mismatchCount mismatches"

        if (mismatchCount > 0) {

          exactLines +=
            s"First ${math.min(mismatchCount, MaxReportedMismatches)} mismatches:"

          exactLines ++=
            formatJoinedDiffRows(
              df = mismatches,
              columns = relevantCols,
              limit = MaxReportedMismatches
            )

          if (mismatchCount > MaxReportedMismatches) {
            exactLines +=
              s"... ${mismatchCount - MaxReportedMismatches} additional mismatches not shown"
          }
        }
      }

      sections += ReportSection(
        title = "Exact-match comparison",
        status = if (exactFailed) Failed else Passed,
        lines = exactLines.toSeq
      )

      println(
        if (exactFailed)
          "Exact-match comparison... FAILED"
        else
          "Exact-match comparison... OK"
      )
    }

    // -------------------------------------------------------------------------
    // Float/Double comparison
    // -------------------------------------------------------------------------

    println("Float/Double comparison...")

    if (failFast) {

      val firstFloatMismatch =
        findFirstFloatMismatch(
          matchedRows = matchedRows,
          businessKeyCols = businessKeyCols,
          floatComparisons = floatComparisons
        )

      firstFloatMismatch match {

        case Some((column, comparison, row)) =>

          sections += ReportSection(
            title = "Float/Double comparison",
            status = Failed,
            lines =
              Seq(
                s"First mismatching column: $column",
                s"Comparison: $comparison",
                "",
                "First mismatching row:",
                ""
              ) ++
                formatJoinedRowDiff(
                  row = row,
                  columns = relevantCols
                )
          )

          val report =
            DataFrameAssertionReport(
              configuration = configuration,
              sections = sections.toSeq
            )

          printReport(report)

          assert(
            assertion = false,
            clue = s"Float/Double comparison failed for column '$column'"
          )

        case None =>

          sections += ReportSection(
            title = "Float/Double comparison",
            status = Passed,
            lines = Seq(
              s"Rows checked: $matchedRowCount",
              "Float comparisons:",
              formatFloatComparisonsMultiline(floatComparisons)
            )
          )

          println("Float/Double comparison... OK")
      }

    } else {

      val floatLines =
        ArrayBuffer[String](
          s"Rows checked: $matchedRowCount"
        )

      var floatFailed =
        false

      floatComparisons.foreach {
        case (column, comparison) =>

          val mismatches =
            filterFloatMismatches(
              df = matchedRows,
              columnName = column,
              comparison = comparison
            )

          val mismatchCount =
            mismatches.count()

          if (mismatchCount > 0) {
            floatFailed = true
          }

          floatLines +=
            s"$column [$comparison]: $mismatchCount mismatches"

          if (mismatchCount > 0) {

            floatLines +=
              s"First ${math.min(mismatchCount, MaxReportedMismatches)} mismatches:"

            floatLines ++=
              formatJoinedDiffRows(
                df = mismatches,
                columns = relevantCols,
                limit = MaxReportedMismatches
              )

            if (mismatchCount > MaxReportedMismatches) {
              floatLines +=
                s"... ${mismatchCount - MaxReportedMismatches} additional mismatches not shown"
            }
          }
      }

      sections += ReportSection(
        title = "Float/Double comparison",
        status = if (floatFailed) Failed else Passed,
        lines = floatLines.toSeq
      )

      println(
        if (floatFailed)
          "Float/Double comparison... FAILED"
        else
          "Float/Double comparison... OK"
      )
    }

    val report =
      DataFrameAssertionReport(
        configuration = configuration,
        sections = sections.toSeq
      )

    printReport(report)

    assert(
      assertion = report.passed,
      clue = "DataFrame comparison failed"
    )
  }


  // ===========================================================================
  // Report model
  // ===========================================================================

  private sealed trait SectionStatus {
    def label: String
  }

  private case object Passed extends SectionStatus {
    override val label: String = "PASSED"
  }

  private case object Failed extends SectionStatus {
    override val label: String = "FAILED"
  }

  private case object NotExecuted extends SectionStatus {
    override val label: String = "NOT EXECUTED"
  }


  private case class ReportSection(
      title: String,
      status: SectionStatus,
      lines: Seq[String]
  )


  private case class DataFrameAssertionReport(
      configuration: Seq[(String, String)],
      sections: Seq[ReportSection]
  ) {

    def passed: Boolean =
      !sections.exists(_.status == Failed)
  }


  // ===========================================================================
  // Schema and configuration validation
  // ===========================================================================

  /**
   * Validates that all specified columns exist in both DataFrames and have
   * matching data types.
   *
   * Additional DataFrame columns are ignored.
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


  private def assertNoColumnOverlap(
      sortCols: Seq[String],
      businessKeyCols: Seq[String],
      exactMatchCols: Seq[String],
      floatComparisons: Map[String, FloatComparison]
  ): Unit = {

    val floatCols =
      floatComparisons.keySet

    val exactFloatOverlap =
      exactMatchCols.toSet.intersect(floatCols)

    require(
      exactFloatOverlap.isEmpty,
      s"Columns cannot be configured as both exact and float comparisons: " +
        exactFloatOverlap.mkString("[", ", ", "]")
    )

    val businessExactOverlap =
      businessKeyCols.toSet.intersect(exactMatchCols.toSet)

    require(
      businessExactOverlap.isEmpty,
      s"Business key columns must not be repeated in exactMatchCols: " +
        businessExactOverlap.mkString("[", ", ", "]")
    )

    val businessFloatOverlap =
      businessKeyCols.toSet.intersect(floatCols)

    require(
      businessFloatOverlap.isEmpty,
      s"Business key columns must not be configured as float comparisons: " +
        businessFloatOverlap.mkString("[", ", ", "]")
    )
  }


  // ===========================================================================
  // Business-key validation
  // ===========================================================================

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


  // ===========================================================================
  // Float comparison
  // ===========================================================================

  /**
   * Shared scalar Float/Double comparison.
   *
   * null == null
   * NaN == NaN
   * +Infinity == +Infinity
   * -Infinity == -Infinity
   *
   * Finite values are delegated to the configured FloatComparison strategy.
   */
  private def floatValuesEqual(
      actValue: Any,
      expValue: Any,
      comparison: FloatComparison
  ): Boolean = {

    (actValue, expValue) match {

      case (null, null) =>
        true

      case (null, _) | (_, null) =>
        false

      case (act: Number, exp: Number) =>

        val actual =
          act.doubleValue()

        val expected =
          exp.doubleValue()

        if (actual.isNaN || expected.isNaN) {
          actual.isNaN && expected.isNaN
        } else if (actual.isInfinity || expected.isInfinity) {
          actual == expected
        } else {
          comparison.isEqual(
            actual,
            expected
          )
        }

      case _ =>
        throw new IllegalArgumentException(
          s"Non-numeric values encountered: actual=$actValue, expected=$expValue"
        )
    }
  }


  /**
   * Filters Float/Double mismatches for a single matched column.
   *
   * The comparison itself is evaluated by a UDF so the DataFrame can still be
   * filtered before mismatch samples are collected on the driver.
   */
  private def filterFloatMismatches(
      df: DataFrame,
      columnName: String,
      comparison: FloatComparison
  ): DataFrame = {

    val isEqualUdf =
      udf {
        (actual: java.lang.Double, expected: java.lang.Double) =>

          floatValuesEqual(
            actValue = actual,
            expValue = expected,
            comparison = comparison
          )
      }

    df.filter(
      !isEqualUdf(
        col(s"act.$columnName").cast("double"),
        col(s"exp.$columnName").cast("double")
      )
    )
  }


  private def findFirstFloatMismatch(
      matchedRows: DataFrame,
      businessKeyCols: Seq[String],
      floatComparisons: Map[String, FloatComparison]
  ): Option[(String, FloatComparison, Row)] = {

    floatComparisons.iterator
      .map {
        case (column, comparison) =>

          val row =
            filterFloatMismatches(
              df = matchedRows,
              columnName = column,
              comparison = comparison
            )
              .orderBy(
                businessKeyCols.map(c => col(s"act.$c")): _*
              )
              .limit(1)
              .collect()
              .headOption

          row.map(r =>
            (
              column,
              comparison,
              r
            )
          )
      }
      .collectFirst {
        case Some(value) => value
      }
  }


  // ===========================================================================
  // CSV-like row formatting
  // ===========================================================================

  private def formatCsvValue(
      value: Any
  ): String =
    if (value == null) "null"
    else value.toString


  private def formatRowDiff(
      actRow: Row,
      expRow: Row,
      columns: Seq[String]
  ): Seq[String] = {

    val header =
      ("_source" +: columns)
        .mkString(",")

    val actLine =
      (
        "ACTUAL" +:
          columns.map(column =>
            formatCsvValue(
              actRow.getAs[Any](column)
            )
          )
      ).mkString(",")

    val expLine =
      (
        "EXPECTED" +:
          columns.map(column =>
            formatCsvValue(
              expRow.getAs[Any](column)
            )
          )
      ).mkString(",")

    Seq(
      header,
      actLine,
      expLine
    )
  }


  private def formatJoinedRowDiff(
      row: Row,
      columns: Seq[String]
  ): Seq[String] = {

    val header =
      ("_source" +: columns)
        .mkString(",")

    val actualValues =
      columns.map { column =>
        formatCsvValue(
          row.getAs[Any](
            row.fieldIndex(s"act.$column")
          )
        )
      }

    val expectedValues =
      columns.map { column =>
        formatCsvValue(
          row.getAs[Any](
            row.fieldIndex(s"exp.$column")
          )
        )
      }

    Seq(
      header,
      ("ACTUAL" +: actualValues).mkString(","),
      ("EXPECTED" +: expectedValues).mkString(",")
    )
  }


  private def formatJoinedSingleRow(
      row: Row,
      sourceAlias: String,
      sourceLabel: String,
      columns: Seq[String]
  ): Seq[String] = {

    val header =
      ("_source" +: columns)
        .mkString(",")

    val values =
      columns.map { column =>
        formatCsvValue(
          row.getAs[Any](
            row.fieldIndex(s"$sourceAlias.$column")
          )
        )
      }

    Seq(
      header,
      (sourceLabel +: values).mkString(",")
    )
  }


  private def formatJoinedRows(
      df: DataFrame,
      sourceAlias: String,
      sourceLabel: String,
      columns: Seq[String],
      limit: Int
  ): Seq[String] = {

    val rows =
      df
        .limit(limit)
        .collect()

    if (rows.isEmpty) {
      Seq.empty
    } else {
      val header =
        ("_source" +: columns)
          .mkString(",")

      header +:
        rows.map { row =>

          val values =
            columns.map { column =>
              formatCsvValue(
                row.getAs[Any](
                  row.fieldIndex(s"$sourceAlias.$column")
                )
              )
            }

          (sourceLabel +: values)
            .mkString(",")
        }.toSeq
    }
  }


  private def formatJoinedDiffRows(
      df: DataFrame,
      columns: Seq[String],
      limit: Int
  ): Seq[String] = {

    val rows =
      df
        .limit(limit)
        .collect()

    if (rows.isEmpty) {
      Seq.empty
    } else {

      val header =
        ("_source" +: columns)
          .mkString(",")

      val lines =
        ArrayBuffer[String](header)

      rows.foreach { row =>

        val actualValues =
          columns.map { column =>
            formatCsvValue(
              row.getAs[Any](
                row.fieldIndex(s"act.$column")
              )
            )
          }

        val expectedValues =
          columns.map { column =>
            formatCsvValue(
              row.getAs[Any](
                row.fieldIndex(s"exp.$column")
              )
            )
          }

        lines +=
          ("ACTUAL" +: actualValues)
            .mkString(",")

        lines +=
          ("EXPECTED" +: expectedValues)
            .mkString(",")
      }

      lines.toSeq
    }
  }


  // ===========================================================================
  // Report formatting
  // ===========================================================================

  private def printReport(
      report: DataFrameAssertionReport
  ): Unit = {

    println()
    println(FullSeparator)
    println(center("| DATAFRAME ASSERTION REPORT |"))
    println(FullSeparator)
    println()

    println("Test configuration:")
    println("-" * ReportWidth)

    report.configuration.foreach {
      case (name, value) =>
        println(
          f"$name%-22s $value"
        )
    }

    println()

    println("Test results:")

    report.sections.foreach { section =>

      println()
      println(SectionSeparator)
      println(
        padSectionTitle(section.title)
      )
      println(SectionSeparator)

      println(
        s"Status: ${section.status.label}"
      )

      if (section.lines.nonEmpty) {
        println()
        section.lines.foreach(println)
      }
    }

    println()
    println(FullSeparator)

    val overall =
      if (report.passed)
        "OVERALL RESULT: PASSED"
      else
        "OVERALL RESULT: FAILED"

    println(
      padSectionTitle(overall)
    )

    println(FullSeparator)
    println()
  }


  private def notExecutedSection(
      title: String
  ): ReportSection =
    ReportSection(
      title = title,
      status = NotExecuted,
      lines = Seq.empty
    )


  private def formatSeq(
      values: Iterable[String]
  ): String =
    values.mkString("[", ", ", "]")


  private def formatFloatComparisons(
      comparisons: Map[String, FloatComparison]
  ): String = {

    if (comparisons.isEmpty) {
      "[]"
    } else {
      comparisons
        .map {
          case (column, comparison) =>
            s"$column -> $comparison"
        }
        .mkString("[", ", ", "]")
    }
  }


  private def formatFloatComparisonsMultiline(
      comparisons: Map[String, FloatComparison]
  ): String = {

    if (comparisons.isEmpty) {
      "  none"
    } else {
      comparisons
        .map {
          case (column, comparison) =>
            s"  $column -> $comparison"
        }
        .mkString("\n")
    }
  }


  private def center(
      text: String
  ): String = {

    val content =
      text.stripPrefix("|").stripSuffix("|").trim

    val innerWidth =
      ReportWidth - 2

    val totalPadding =
      math.max(0, innerWidth - content.length)

    val left =
      totalPadding / 2

    val right =
      totalPadding - left

    "|" +
      (" " * left) +
      content +
      (" " * right) +
      "|"
  }


  private def padSectionTitle(
      title: String
  ): String = {

    val innerWidth =
      ReportWidth - 2

    val content =
      s" $title"

    "|" +
      content.padTo(innerWidth, ' ') +
      "|"
  }
}
