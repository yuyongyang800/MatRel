package org.apache.spark.sql.matfast.execution

import org.apache.spark.sql.matfast.matrix._
import org.apache.spark.sql.matfast.util._
import org.apache.spark.rdd.RDD
import org.apache.spark.Partitioner
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, GenericInternalRow}
import org.apache.spark.sql.execution.{SparkPlan}
import org.apache.spark.sql.matfast.partitioner.{BlockCyclicPartitioner, ColumnPartitioner, RowPartitioner}

import scala.collection.concurrent.TrieMap

/**
  * Created by yongyangyu on 2/28/17.
  */
case class MatrixTransposeExecution(child: SparkPlan) extends MatfastPlan {

  override def output: Seq[Attribute] = child.output

  override def children: Seq[SparkPlan] = child :: Nil

  protected override def doExecute(): RDD[InternalRow] = {
    val rootRdd = child.execute()
    rootRdd.map { row =>
      val rid = row.getInt(0)
      val cid = row.getInt(1)
      val matrixInternalRow = row.getStruct(2, 7)
      val res = new GenericInternalRow(3)
      val matrix = MLMatrixSerializer.deserialize(matrixInternalRow)
      val matrixRow = MLMatrixSerializer.serialize(matrix.transpose)
      res.setInt(0, cid)
      res.setInt(1, rid)
      res.update(2, matrixRow)
      res
    }
  }
}

case class MatrixScalarAddExecution(child: SparkPlan, alpha: Double) extends MatfastPlan {

  override def output: Seq[Attribute] = child.output

  override def children: Seq[SparkPlan] = child :: Nil

  protected override def doExecute(): RDD[InternalRow] = {
    val rootRdd = child.execute()
    rootRdd.map { row =>
      val rid = row.getInt(0)
      val cid = row.getInt(1)
      val matrixInternalRow = row.getStruct(2, 7)
      val res = new GenericInternalRow(3)
      val matrix = MLMatrixSerializer.deserialize(matrixInternalRow)
      val matrixRow = MLMatrixSerializer.serialize(LocalMatrix.addScalar(matrix, alpha))
      res.setInt(0, rid)
      res.setInt(1, cid)
      res.update(2, matrixRow)
      res
    }
  }
}

case class MatrixScalarMultiplyExecution(child: SparkPlan, alpha: Double) extends MatfastPlan {

  override def output: Seq[Attribute] = child.output

  override def children: Seq[SparkPlan] = child :: Nil

  protected override def doExecute(): RDD[InternalRow] = {
    val rootRdd = child.execute()
    rootRdd.map { row =>
      val rid = row.getInt(0)
      val cid = row.getInt(1)
      val matrixInternalRow = row.getStruct(2, 7)
      val res = new GenericInternalRow(3)
      val matrix = MLMatrixSerializer.deserialize(matrixInternalRow)
      val matrixRow = MLMatrixSerializer.serialize(LocalMatrix.multiplyScalar(alpha, matrix))
      res.setInt(0, rid)
      res.setInt(1, cid)
      res.update(2, matrixRow)
      res
    }
  }
}

case class MatrixElementAddExecution(left: SparkPlan,
                                     leftRowNum: Long,
                                     leftColNum: Long,
                                     right: SparkPlan,
                                     rightRowNum: Long,
                                     rightColNum: Long,
                                     blkSize: Int) extends MatfastPlan {

  override def output: Seq[Attribute] = left.output

  override def children: Seq[SparkPlan] = Seq(left, right)

  protected override def doExecute(): RDD[InternalRow] = {
    require(leftRowNum == rightRowNum, s"Row number not match, leftRowNum = $leftRowNum, rightRowNum = $rightRowNum")
    require(leftColNum == rightColNum, s"Col number not match, leftColNum = $leftColNum, rightColNum = $rightColNum")
    val rdd1 = left.execute()
    val rdd2 = right.execute()
    if (rdd1.partitioner != None) {
      val part = rdd1.partitioner.get
      MatfastExecutionHelper.addWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    } else if (rdd2.partitioner != None) {
      val part = rdd2.partitioner.get
      MatfastExecutionHelper.addWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    } else {
      val params = MatfastExecutionHelper.genBlockCyclicPartitioner(leftRowNum, leftColNum, blkSize)
      val part = new BlockCyclicPartitioner(params._1, params._2, params._3, params._4)
      MatfastExecutionHelper.addWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    }
  }
}

case class MatrixElementMultiplyExecution(left: SparkPlan,
                                          leftRowNum: Long,
                                          leftColNum: Long,
                                          right: SparkPlan,
                                          rightRowNum: Long,
                                          rightColNum: Long,
                                          blkSize: Int) extends MatfastPlan {

  override def output: Seq[Attribute] = left.output

  override def children: Seq[SparkPlan] = Seq(left, right)

  protected override def doExecute(): RDD[InternalRow] = {
    require(leftRowNum == rightRowNum, s"Row number not match, leftRowNum = $leftRowNum, rightRowNum = $rightRowNum")
    require(leftColNum == rightColNum, s"Col number not match, leftColNum = $leftColNum, rightColNum = $rightColNum")
    val rdd1 = left.execute()
    val rdd2 = right.execute()
    if (rdd1.partitioner != None) {
      val part = rdd1.partitioner.get
      MatfastExecutionHelper.multiplyWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    } else if (rdd2.partitioner != None) {
      val part = rdd2.partitioner.get
      MatfastExecutionHelper.multiplyWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    } else {
      val params = MatfastExecutionHelper.genBlockCyclicPartitioner(leftRowNum, leftColNum, blkSize)
      val part = new BlockCyclicPartitioner(params._1, params._2, params._3, params._4)
      MatfastExecutionHelper.multiplyWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    }
  }
}

case class MatrixElementDivideExecution(left: SparkPlan,
                                        leftRowNum: Long,
                                        leftColNum: Long,
                                        right: SparkPlan,
                                        rightRowNum: Long,
                                        rightColNum: Long,
                                        blkSize: Int) extends MatfastPlan {

  override def output: Seq[Attribute] = left.output

  override def children: Seq[SparkPlan] = Seq(left, right)

  protected override def doExecute(): RDD[InternalRow] = {
    require(leftRowNum == rightRowNum, s"Row number not match, leftRowNum = $leftRowNum, rightRowNum = $rightRowNum")
    require(leftColNum == rightColNum, s"Col number not match, leftColNum = $leftColNum, rightColNum = $rightColNum")
    val rdd1 = left.execute()
    val rdd2 = right.execute()
    if (rdd1.partitioner != None) {
      val part = rdd1.partitioner.get
      MatfastExecutionHelper.divideWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    } else if (rdd2.partitioner != None) {
      val part = rdd2.partitioner.get
      MatfastExecutionHelper.divideWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    } else {
      val params = MatfastExecutionHelper.genBlockCyclicPartitioner(leftRowNum, leftColNum, blkSize)
      val part = new BlockCyclicPartitioner(params._1, params._2, params._3, params._4)
      MatfastExecutionHelper.divideWithPartitioner(MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd1),
        MatfastExecutionHelper.repartitionWithTargetPartitioner(part, rdd2))
    }
  }
}