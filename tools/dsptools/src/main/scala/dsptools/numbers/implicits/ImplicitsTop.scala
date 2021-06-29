// SPDX-License-Identifier: Apache-2.0

package dsptools.numbers

import chisel3.experimental.FixedPoint
import dsptools.numbers.chisel_types.IntervalImpl

trait AllSyntax extends EqSyntax with PartialOrderSyntax with OrderSyntax with SignedSyntax with IsRealSyntax with IsIntegerSyntax with
  ConvertableToSyntax with ChiselConvertableFromSyntax with BinaryRepresentationSyntax with ContextualRingSyntax

trait AllImpl extends UIntImpl with SIntImpl with IntervalImpl with FixedPointImpl with DspRealImpl with DspComplexImpl

object implicits extends AllSyntax with AllImpl with spire.syntax.AllSyntax {
}