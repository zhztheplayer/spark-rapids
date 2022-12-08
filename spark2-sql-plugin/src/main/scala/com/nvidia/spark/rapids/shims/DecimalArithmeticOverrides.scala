/*
 * Copyright (c) 2022, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids.shims

import com.nvidia.spark.rapids.{BaseExprMeta, CastExprMeta, DecimalUtil, ExprChecks, ExprMeta, ExprRule, GpuOverrides, LiteralExprMeta, TypeSig, UnaryExprMeta}
import com.nvidia.spark.rapids.GpuOverrides.expr

import org.apache.spark.sql.catalyst.expressions.{Cast, CheckOverflow, Divide, Expression, Literal, Multiply, PromotePrecision}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.rapids.{GpuDecimalDivide, GpuDecimalMultiply}
import org.apache.spark.sql.types.{Decimal, DecimalType}

object DecimalArithmeticOverrides {
  def exprs: Map[Class[_ <: Expression], ExprRule[_ <: Expression]] = Seq(
    GpuOverrides.expr[PromotePrecision](
      "PromotePrecision before arithmetic operations between DecimalType data",
      ExprChecks.unaryProjectInputMatchesOutput(TypeSig.DECIMAL_128,
        TypeSig.DECIMAL_128),
      (a, conf, p, r) => new UnaryExprMeta[PromotePrecision](a, conf, p, r) {

      }),
    expr[CheckOverflow](
      "CheckOverflow after arithmetic operations between DecimalType data",
      ExprChecks.unaryProjectInputMatchesOutput(TypeSig.DECIMAL_128,
        TypeSig.DECIMAL_128),
      (a, conf, p, r) => new ExprMeta[CheckOverflow](a, conf, p, r) {
        private[this] def extractOrigParam(expr: BaseExprMeta[_]): BaseExprMeta[_] =
          expr.wrapped match {
            case lit: Literal if lit.dataType.isInstanceOf[DecimalType] =>
              // Lets figure out if we can make the Literal value smaller
              val (newType, value) = lit.value match {
                case null =>
                  (DecimalType(0, 0), null)
                case dec: Decimal =>
                  val stripped = Decimal(dec.toJavaBigDecimal.stripTrailingZeros())
                  val p = stripped.precision
                  val s = stripped.scale
                  // allowNegativeScaleOfDecimalEnabled is not in 2.x assume its default false
                  val t = if (s < 0 && !false) {
                    // need to adjust to avoid errors about negative scale
                    DecimalType(p - s, 0)
                  } else {
                    DecimalType(p, s)
                  }
                  (t, stripped)
                case other =>
                  throw new IllegalArgumentException(s"Unexpected decimal literal value $other")
              }
              expr.asInstanceOf[LiteralExprMeta].withNewLiteral(Literal(value, newType))
            // Avoid unapply for PromotePrecision and Cast because it changes between Spark
            // versions
            // Spark 2.X only has Cast, no AnsiCast so no CastBase, hardcode here to Cast
            case p: PromotePrecision if p.child.isInstanceOf[Cast] &&
                p.child.dataType.isInstanceOf[DecimalType] =>
              val c = p.child.asInstanceOf[Cast]
              val to = c.dataType.asInstanceOf[DecimalType]
              val fromType = DecimalUtil.optionallyAsDecimalType(c.child.dataType)
              fromType match {
                case Some(from) =>
                  val minScale = math.min(from.scale, to.scale)
                  val fromWhole = from.precision - from.scale
                  val toWhole = to.precision - to.scale
                  val minWhole = if (to.scale < from.scale) {
                    // If the scale is getting smaller in the worst case we need an
                    // extra whole part to handle rounding up.
                    math.min(fromWhole + 1, toWhole)
                  } else {
                    math.min(fromWhole, toWhole)
                  }
                  val newToType = DecimalType(minWhole + minScale, minScale)
                  if (newToType == from) {
                    // We can remove the cast totally
                    val castExpr = expr.childExprs.head
                    castExpr.childExprs.head
                  } else if (newToType == to) {
                    // The cast is already ideal
                    expr
                  } else {
                    val castExpr = expr.childExprs.head.asInstanceOf[CastExprMeta[_]]
                    castExpr.withToTypeOverride(newToType)
                  }
                case _ =>
                  expr
              }
            case _ => expr
          }
        private[this] lazy val binExpr = childExprs.head
        private[this] lazy val lhs = extractOrigParam(binExpr.childExprs.head)
        private[this] lazy val rhs = extractOrigParam(binExpr.childExprs(1))
        private[this] lazy val lhsDecimalType =
          DecimalUtil.asDecimalType(lhs.wrapped.asInstanceOf[Expression].dataType)
        private[this] lazy val rhsDecimalType =
          DecimalUtil.asDecimalType(rhs.wrapped.asInstanceOf[Expression].dataType)

      })
  ).map(r => (r.getClassFor.asSubclass(classOf[Expression]), r)).toMap
}