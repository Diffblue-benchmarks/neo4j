/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compatibility.v3_4.runtime

import org.neo4j.cypher.internal.compiler.v3_4.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.frontend.v3_4.ast.ASTAnnotationMap
import org.neo4j.cypher.internal.frontend.v3_4.semantics.{ExpressionTypeInfo, SemanticTable}
import org.neo4j.cypher.internal.ir.v3_4.{CardinalityEstimation, PlannerQuery, VarPatternLength}
import org.neo4j.cypher.internal.util.v3_4.LabelId
import org.neo4j.cypher.internal.util.v3_4.symbols._
import org.neo4j.cypher.internal.util.v3_4.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.v3_4.expressions._
import org.neo4j.cypher.internal.v3_4.logical.plans.{Ascending, _}
import org.neo4j.cypher.internal.v3_4.logical.{plans => logicalPlans}

//noinspection NameBooleanParameters
class SlotAllocationTest extends CypherFunSuite with LogicalPlanningTestSupport2 {

  private val x = "x"
  private val y = "y"
  private val z = "z"
  private val LABEL = LabelName("label")(pos)
  private val r = "r"
  private val r2 = "r2"
  private val semanticTable = SemanticTable()

  test("only single allnodes scan") {
    // given
    val plan = AllNodesScan(x, Set.empty)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(plan, semanticTable).slotConfigurations

    // then
    allocations should have size 1
    allocations(plan.id) should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), 1, 0))
  }

  test("limit should not introduce slots") {
    // given
    val plan = logicalPlans.Limit(AllNodesScan(x, Set.empty)(solved), literalInt(1), DoNotIncludeTies)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(plan, semanticTable).slotConfigurations

    // then
    allocations should have size 2
    allocations(plan.id) should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), 1, 0))
  }

  test("single labelscan scan") {
    // given
    val plan = NodeByLabelScan(x, LABEL, Set.empty)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(plan, semanticTable).slotConfigurations

    // then
    allocations should have size 1
    allocations(plan.id) should equal(SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), 1, 0))
  }

  test("labelscan with filtering") {
    // given
    val leaf = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val filter = Selection(Seq(True()(pos)), leaf)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(filter, semanticTable).slotConfigurations

    // then
    allocations should have size 2
    allocations(leaf.id) should equal(SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), 1, 0))
    allocations(filter.id) shouldBe theSameInstanceAs(allocations(leaf.id))
  }

  test("single node with expand") {
    // given
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val expand = Expand(allNodesScan, x, SemanticDirection.INCOMING, Seq.empty, z, r, ExpandAll)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(expand, semanticTable).slotConfigurations

    // then we'll end up with two pipelines
    allocations should have size 2
    val labelScanAllocations = allocations(allNodesScan.id)
    labelScanAllocations should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), numberOfLongs = 1, numberOfReferences = 0))

    val expandAllocations = allocations(expand.id)
    expandAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "r" -> LongSlot(1, nullable = false, CTRelationship),
        "z" -> LongSlot(2, nullable = false, CTNode)), numberOfLongs = 3, numberOfReferences = 0))
  }

  test("single node with expand into") {
    // given
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val expand = Expand(allNodesScan, x, SemanticDirection.INCOMING, Seq.empty, x, r, ExpandInto)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(expand, semanticTable).slotConfigurations

    // then we'll end up with two pipelines
    allocations should have size 2
    val labelScanAllocations = allocations(allNodesScan.id)
    labelScanAllocations should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), numberOfLongs = 1, numberOfReferences = 0))

    val expandAllocations = allocations(expand.id)
    expandAllocations should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode), "r" -> LongSlot(1, nullable = false,
        CTRelationship)), numberOfLongs = 2, numberOfReferences = 0))
  }

  test("optional node") {
    // given
    val leaf = AllNodesScan(x, Set.empty)(solved)
    val plan = Optional(leaf)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(plan, semanticTable).slotConfigurations

    // then
    allocations should have size 2
    allocations(plan.id) should equal(SlotConfiguration(Map("x" -> LongSlot(0, nullable = true, CTNode)), 1, 0))
  }

  test("single node with optionalExpand ExpandAll") {
    // given
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val expand = OptionalExpand(allNodesScan, x, SemanticDirection.INCOMING, Seq.empty, z, r, ExpandAll)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(expand, semanticTable).slotConfigurations

    // then we'll end up with two pipelines
    allocations should have size 2
    val labelScanAllocations = allocations(allNodesScan.id)
    labelScanAllocations should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), numberOfLongs = 1, numberOfReferences =
        0))

    val expandAllocations = allocations(expand.id)
    expandAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "r" -> LongSlot(1, nullable = true, CTRelationship),
        "z" -> LongSlot(2, nullable = true, CTNode)
      ), numberOfLongs = 3, numberOfReferences = 0))
  }

  test("single node with optionalExpand ExpandInto") {
    // given
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val expand = OptionalExpand(allNodesScan, x, SemanticDirection.INCOMING, Seq.empty, x, r, ExpandInto)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(expand, semanticTable).slotConfigurations

    // then we'll end up with two pipelines
    allocations should have size 2
    val labelScanAllocations = allocations(allNodesScan.id)
    labelScanAllocations should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), numberOfLongs = 1, numberOfReferences = 0))

    val expandAllocations = allocations(expand.id)
    expandAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "r" -> LongSlot(1, nullable = true, CTRelationship)
      ), numberOfLongs = 2, numberOfReferences = 0))
  }

  test("single node with var length expand") {
    // given
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val varLength = VarPatternLength(1, Some(15))
    val tempNode = "r_NODES"
    val tempEdge = "r_EDGES"
    val expand = VarExpand(allNodesScan, x, SemanticDirection.INCOMING, SemanticDirection.INCOMING, Seq.empty, z, r,
      varLength, ExpandAll, tempNode, tempEdge, True()(pos), True()(pos), Seq.empty)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(expand, semanticTable).slotConfigurations

    // then we'll end up with two pipelines
    allocations should have size 2
    val allNodeScanAllocations = allocations(allNodesScan.id)
    allNodeScanAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "r_NODES" -> LongSlot(1, nullable = false, CTNode),
        "r_EDGES" -> LongSlot(2, nullable = false, CTRelationship)),
        numberOfLongs = 3, numberOfReferences = 0))

    val expandAllocations = allocations(expand.id)
    expandAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "r" -> RefSlot(0, nullable = false, CTList(CTRelationship)),
        "z" -> LongSlot(1, nullable = false, CTNode)), numberOfLongs = 2, numberOfReferences = 1))
  }

  test("single node with var length expand into") {
    // given
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val expand = Expand(allNodesScan, x, SemanticDirection.OUTGOING, Seq.empty, y, r, ExpandAll)(solved)
    val varLength = VarPatternLength(1, Some(15))
    val tempNode = "r_NODES"
    val tempEdge = "r_EDGES"
    val varExpand = VarExpand(expand, x, SemanticDirection.INCOMING, SemanticDirection.INCOMING, Seq.empty, y, r2,
      varLength, ExpandInto, tempNode, tempEdge, True()(pos), True()(pos), Seq.empty)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(varExpand, semanticTable).slotConfigurations

    // then we'll end up with three pipelines
    allocations should have size 3
    val allNodeScanAllocations = allocations(allNodesScan.id)
    allNodeScanAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode)),
        numberOfLongs = 1, numberOfReferences = 0))

    val expandAllocations = allocations(expand.id)
    expandAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "r" -> LongSlot(1, nullable = false, CTRelationship),
        "y" -> LongSlot(2, nullable = false, CTNode),
        "r_NODES" -> LongSlot(3, nullable = false, CTNode),
        "r_EDGES" -> LongSlot(4, nullable = false, CTRelationship)),
        numberOfLongs = 5, numberOfReferences = 0))

    val varExpandAllocations = allocations(varExpand.id)
    varExpandAllocations should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "r" -> LongSlot(1, nullable = false, CTRelationship),
        "y" -> LongSlot(2, nullable = false, CTNode),
        "r2" -> RefSlot(0, nullable = false, CTList(CTRelationship))),
        numberOfLongs = 3, numberOfReferences = 1))
  }

  test("let's skip this one") {
    // given
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val skip = logicalPlans.Skip(allNodesScan, literalInt(42))(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(skip, semanticTable).slotConfigurations

    // then
    allocations should have size 2
    val labelScanAllocations = allocations(allNodesScan.id)
    labelScanAllocations should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), numberOfLongs = 1, numberOfReferences = 0))

    val expandAllocations = allocations(skip.id)
    expandAllocations shouldBe theSameInstanceAs(labelScanAllocations)
  }

  test("all we need is to apply ourselves") {
    // given
    val lhs = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val label = LabelToken("label2", LabelId(0))
    val seekExpression = SingleQueryExpression(literalInt(42))
    val rhs = NodeIndexSeek(z, label, Seq.empty, seekExpression, Set(x))(solved)
    val apply = Apply(lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(apply, semanticTable).slotConfigurations

    // then
    allocations should have size 3
    allocations(lhs.id) should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), numberOfLongs = 1, numberOfReferences = 0))

    val rhsPipeline = allocations(rhs.id)

    rhsPipeline should equal(
      SlotConfiguration(Map(
        "x" -> LongSlot(0, nullable = false, CTNode),
        "z" -> LongSlot(1, nullable = false, CTNode)
      ), numberOfLongs = 2, numberOfReferences = 0))

    allocations(apply.id) shouldBe theSameInstanceAs(rhsPipeline)
  }

  test("aggregation used for distinct") {
    // given
    val leaf = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val distinct = Aggregation(leaf, Map("x" -> varFor("x")), Map.empty)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(distinct, semanticTable).slotConfigurations

    // then
    val expected = SlotConfiguration.empty.newLong("x", false, CTNode)

    allocations should have size 2
    allocations(leaf.id) should equal(expected)
    allocations(distinct.id) should equal(expected)
  }

  test("optional travels through aggregation used for distinct") {
    // given OPTIONAL MATCH (x) RETURN DISTINCT x, x.propertyKey
    val leaf = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val optional = Optional(leaf)(solved)
    val distinct = Distinct(optional, Map("x" -> varFor("x"), "x.propertyKey" -> prop("x", "propertyKey")))(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(distinct, semanticTable).slotConfigurations

    // then
    val leafExpected = SlotConfiguration.empty.newLong("x", true, CTNode)
    val distinctExpected =
      SlotConfiguration.empty
        .newLong("x", true, CTNode)
        .newReference("x.propertyKey", true, CTAny)

    allocations should have size 3
    allocations(leaf.id) should equal(leafExpected)
    allocations(optional.id) should equal(leafExpected)
    allocations(distinct.id) should equal(distinctExpected)
  }

  test("optional travels through aggregation") {
    // given OPTIONAL MATCH (x) RETURN x, x.propertyKey, count(*)
    val leaf = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val optional = Optional(leaf)(solved)
    val countStar = Aggregation(optional,
      groupingExpressions = Map("x" -> varFor("x"),
        "x.propertyKey" -> prop("x", "propertyKey")),
      aggregationExpression = Map("count(*)" -> CountStar()(pos)))(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(countStar, semanticTable).slotConfigurations

    // then
    val leafExpected = SlotConfiguration.empty.newLong("x", true, CTNode)
    val aggrExpected =
      SlotConfiguration.empty
        .newLong("x", true, CTNode)
        .newReference("x.propertyKey", true, CTAny)
        .newReference("count(*)", true, CTAny)

    allocations should have size 3
    allocations(leaf.id) should equal(leafExpected)

    allocations(optional.id) should be theSameInstanceAs allocations(leaf.id)
    allocations(countStar.id) should equal(aggrExpected)
  }

  test("labelscan with projection") {
    // given
    val leaf = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val projection = Projection(leaf, Map("x" -> varFor("x"), "x.propertyKey" -> prop("x", "propertyKey")))(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(projection, semanticTable).slotConfigurations

    // then
    allocations should have size 2
    allocations(leaf.id) should equal(SlotConfiguration(numberOfLongs = 1, numberOfReferences = 1, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "x.propertyKey" -> RefSlot(0, nullable = true, CTAny)
    )))
    allocations(projection.id) shouldBe theSameInstanceAs(allocations(leaf.id))
  }

  test("cartesian product") {
    // given
    val lhs = NodeByLabelScan(x, LabelName("label1")(pos), Set.empty)(solved)
    val rhs = NodeByLabelScan(y, LabelName("label2")(pos), Set.empty)(solved)
    val Xproduct = CartesianProduct(lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(Xproduct, semanticTable).slotConfigurations

    // then
    allocations should have size 3
    allocations(lhs.id) should equal(SlotConfiguration(numberOfLongs = 1, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode)
    )))
    allocations(rhs.id) should equal(SlotConfiguration(numberOfLongs = 1, numberOfReferences = 0, slots = Map(
      "y" -> LongSlot(0, nullable = false, CTNode)
    )))
    allocations(Xproduct.id) should equal(SlotConfiguration(numberOfLongs = 2, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "y" -> LongSlot(1, nullable = false, CTNode)
    )))

  }

  test("cartesian product should allocate lhs followed by rhs, in order") {
    def expand(n:Int): LogicalPlan =
      n match {
        case 1 => NodeByLabelScan("n1", LabelName("label2")(pos), Set.empty)(solved)
        case n => Expand(expand(n-1), "n"+(n-1), SemanticDirection.INCOMING, Seq.empty, "n"+n, "r"+(n-1), ExpandAll)(solved)
      }
    val N = 10

    // given
    val lhs = NodeByLabelScan(x, LabelName("label1")(pos), Set.empty)(solved)
    val rhs = expand(N)
    val Xproduct = CartesianProduct(lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(Xproduct, semanticTable).slotConfigurations

    // then
    allocations should have size N+2

    val expectedPipelines =
      (1 until N).foldLeft(allocations(lhs.id))(
        (acc, i) =>
          acc
            .newLong("n"+i, false, CTNode)
            .newLong("r"+i, false, CTRelationship)
      ).newLong("n"+N, false, CTNode)

    allocations(Xproduct.id) should equal(expectedPipelines)
  }

  test("node hash join I") {
    // given
    val lhs = NodeByLabelScan(x, LabelName("label1")(pos), Set.empty)(solved)
    val rhs = NodeByLabelScan(x, LabelName("label2")(pos), Set.empty)(solved)
    val hashJoin = NodeHashJoin(Set(x), lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(hashJoin, semanticTable).slotConfigurations

    // then
    allocations should have size 3
    allocations(lhs.id) should equal(SlotConfiguration(numberOfLongs = 1, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode)
    )))
    allocations(rhs.id) should equal(SlotConfiguration(numberOfLongs = 1, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode)
    )))
    allocations(hashJoin.id) should equal(SlotConfiguration(numberOfLongs = 1, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode)
    )))
  }

  test("node hash join II") {
    // given
    val lhs = NodeByLabelScan(x, LabelName("label1")(pos), Set.empty)(solved)
    val lhsE = Expand(lhs, x, SemanticDirection.INCOMING, Seq.empty, y, r, ExpandAll)(solved)

    val rhs = NodeByLabelScan(x, LabelName("label2")(pos), Set.empty)(solved)
    val r2 = "r2"
    val rhsE = Expand(rhs, x, SemanticDirection.INCOMING, Seq.empty, z, r2, ExpandAll)(solved)

    val hashJoin = NodeHashJoin(Set(x), lhsE, rhsE)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(hashJoin, semanticTable).slotConfigurations

    // then
    allocations should have size 5
    allocations(lhsE.id) should equal(SlotConfiguration(numberOfLongs = 3, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "r" -> LongSlot(1, nullable = false, CTRelationship),
      "y" -> LongSlot(2, nullable = false, CTNode)
    )))
    allocations(rhsE.id) should equal(SlotConfiguration(numberOfLongs = 3, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "r2" -> LongSlot(1, nullable = false, CTRelationship),
      "z" -> LongSlot(2, nullable = false, CTNode)
    )))
    allocations(hashJoin.id) should equal(SlotConfiguration(numberOfLongs = 5, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "r" -> LongSlot(1, nullable = false, CTRelationship),
      "y" -> LongSlot(2, nullable = false, CTNode),
      "r2" -> LongSlot(3, nullable = false, CTRelationship),
      "z" -> LongSlot(4, nullable = false, CTNode)

    )))
  }

  test("node hash join III") {
    // given
    val lhs = NodeByLabelScan(x, LabelName("label1")(pos), Set.empty)(solved)
    val lhsE = Expand(lhs, x, SemanticDirection.INCOMING, Seq.empty, y, r, ExpandAll)(solved)

    val rhs = NodeByLabelScan(x, LabelName("label2")(pos), Set.empty)(solved)
    val rhsE = Expand(rhs, x, SemanticDirection.INCOMING, Seq.empty, y, "r2", ExpandAll)(solved)

    val hashJoin = NodeHashJoin(Set(x, y), lhsE, rhsE)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(hashJoin, semanticTable).slotConfigurations

    // then
    allocations should have size 5 // One for each label-scan and expand, and one after the join
    allocations(lhsE.id) should equal(SlotConfiguration(numberOfLongs = 3, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "r" -> LongSlot(1, nullable = false, CTRelationship),
      "y" -> LongSlot(2, nullable = false, CTNode)
    )))
    allocations(rhsE.id) should equal(SlotConfiguration(numberOfLongs = 3, numberOfReferences = 0, slots = Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "r2" -> LongSlot(1, nullable = false, CTRelationship),
      "y" -> LongSlot(2, nullable = false, CTNode)
    )))
    allocations(hashJoin.id) should equal(SlotConfiguration(numberOfLongs = 4, numberOfReferences = 0, slots =
      Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "r" -> LongSlot(1, nullable = false, CTRelationship),
      "y" -> LongSlot(2, nullable = false, CTNode),
      "r2" -> LongSlot(3, nullable = false, CTRelationship)
    )))
  }

  test("that argument does not apply here") {
    // given MATCH (x) MATCH (x)<-[r]-(y)
    val lhs = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val arg = Argument(Set(x))(solved)
    val rhs = Expand(arg, x, SemanticDirection.INCOMING, Seq.empty, y, r, ExpandAll)(solved)

    val apply = Apply(lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(apply, semanticTable).slotConfigurations

    // then
    val lhsPipeline = SlotConfiguration(Map(
      "x" -> LongSlot(0, nullable = false, CTNode)),
      numberOfLongs = 1, numberOfReferences = 0)

    val rhsPipeline = SlotConfiguration(Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "y" -> LongSlot(2, nullable = false, CTNode),
      "r" -> LongSlot(1, nullable = false, CTRelationship)
    ), numberOfLongs = 3, numberOfReferences = 0)

    allocations should have size 4
    allocations(arg.id) should equal(lhsPipeline)
    allocations(lhs.id) should equal(lhsPipeline)
    allocations(apply.id) should equal(rhsPipeline)
    allocations(rhs.id) should equal(rhsPipeline)
  }

  test("unwind and project") {
    // given UNWIND [1,2,3] as x RETURN x
    val leaf = Argument()(solved)
    val unwind = UnwindCollection(leaf, "x", listOf(literalInt(1), literalInt(2), literalInt(3)))(solved)
    val produceResult = ProduceResult(unwind, Seq("x"))

    // when
    val allocations = SlotAllocation.allocateSlots(produceResult, semanticTable).slotConfigurations

    // then
    allocations should have size 3
    allocations(leaf.id) should equal(SlotConfiguration(Map.empty, 0, 0))


    allocations(unwind.id) should equal(SlotConfiguration(numberOfLongs = 0, numberOfReferences = 1, slots = Map(
      "x" -> RefSlot(0, nullable = true, CTAny)
    )))
    allocations(produceResult.id) shouldBe theSameInstanceAs(allocations(unwind.id))
  }

  test("unwind and project and sort") {
    // given UNWIND [1,2,3] as x RETURN x ORDER BY x
    val xVarName = "x"
    val xVar = varFor(xVarName)
    val leaf = Argument()(solved)
    val unwind = UnwindCollection(leaf, xVarName, listOf(literalInt(1), literalInt(2), literalInt(3)))(solved)
    val sort = Sort(unwind, List(Ascending(xVarName)))(solved)
    val produceResult = ProduceResult(sort, Seq("x"))

    // when
    val allocations = SlotAllocation.allocateSlots(produceResult, semanticTable).slotConfigurations

    // then
    allocations should have size 4
    allocations(leaf.id) should equal(SlotConfiguration(Map.empty, 0, 0))


    val expectedPipeline = SlotConfiguration(numberOfLongs = 0, numberOfReferences = 1, slots = Map(
      "x" -> RefSlot(0, nullable = true, CTAny)
    ))
    allocations(unwind.id) should equal(expectedPipeline)
    allocations(sort.id) shouldBe theSameInstanceAs(allocations(unwind.id))
    allocations(produceResult.id) shouldBe theSameInstanceAs(allocations(unwind.id))
  }

  test("semi apply") {
    // MATCH (x) WHERE (x) -[:r]-> (y) ....
    testSemiApply(SemiApply(_,_))
  }

  test("anti semi apply") {
    // MATCH (x) WHERE NOT (x) -[:r]-> (y) ....
    testSemiApply(AntiSemiApply(_,_))
  }

  def testSemiApply(
                     semiApplyBuilder: (LogicalPlan, LogicalPlan) =>
                       PlannerQuery with CardinalityEstimation => AbstractSemiApply
                   ): Unit = {
    val lhs = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val arg = Argument(Set(x))(solved)
    val rhs = Expand(arg, x, SemanticDirection.INCOMING, Seq.empty, y, r, ExpandAll)(solved)
    val semiApply = semiApplyBuilder(lhs, rhs)(solved)
    val allocations = SlotAllocation.allocateSlots(semiApply, semanticTable).slotConfigurations

    val lhsPipeline = SlotConfiguration(Map(
      "x" -> LongSlot(0, nullable = false, CTNode)),
      numberOfLongs = 1, numberOfReferences = 0)

    val argumentSide = lhsPipeline

    val rhsPipeline = SlotConfiguration(Map(
      "x" -> LongSlot(0, nullable = false, CTNode),
      "y" -> LongSlot(2, nullable = false, CTNode),
      "r" -> LongSlot(1, nullable = false, CTRelationship)
    ), numberOfLongs = 3, numberOfReferences = 0)

    allocations should have size 4
    allocations(semiApply.id) should equal(lhsPipeline)
    allocations(lhs.id) should equal(lhsPipeline)
    allocations(rhs.id) should equal(rhsPipeline)
    allocations(arg.id) should equal(argumentSide)
  }

  test("argument on two sides of Apply") {
    val arg1 = Argument()(solved)
    val arg2 = Argument()(solved)
    val pr1 = Projection(arg1, Map("x" -> literalInt(42)))(solved)
    val pr2 = Projection(arg2, Map("y" -> literalInt(666)))(solved)
    val apply = Apply(pr1, pr2)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(apply, semanticTable).slotConfigurations

    // then
    allocations should have size 5
    val lhsPipeline = SlotConfiguration.empty.newReference("x", true, CTAny)
    val rhsPipeline =
      SlotConfiguration.empty
        .newReference("x", true, CTAny)
        .newReference("y", true, CTAny)

    allocations(arg1.id) should equal(lhsPipeline)
    allocations(pr1.id) should equal(lhsPipeline)
    allocations(arg2.id) should equal(rhsPipeline)
    allocations(pr2.id) should equal(rhsPipeline)
    allocations(apply.id) should equal(rhsPipeline)
  }

  test("should allocate aggregation") {
    // Given MATCH (x)-[r:R]->(y) RETURN x, x.prop, count(r.prop)
    val labelScan = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val expand = Expand(labelScan, x, SemanticDirection.INCOMING, Seq.empty, y, r, ExpandAll)(solved)

    val grouping = Map(
      "x" -> varFor("x"),
      "x.prop" -> prop("x", "prop")
    )
    val aggregations = Map(
      "count(r.prop)" -> FunctionInvocation(FunctionName("count")(pos), prop("r", "prop"))(pos)
    )
    val aggregation = Aggregation(expand, grouping, aggregations)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(aggregation, semanticTable).slotConfigurations

    allocations should have size 3
    allocations(expand.id) should equal(
      SlotConfiguration.empty
        .newLong("x", false, CTNode)
        .newLong("r", false, CTRelationship)
        .newLong("y", false, CTNode))

    allocations(aggregation.id) should equal(
      SlotConfiguration.empty
        .newLong("x", false, CTNode)
        .newReference("x.prop", true, CTAny)
        .newReference("count(r.prop)", true, CTAny))
  }

  test("should allocate RollUpApply") {
    // Given RollUpApply with RHS ~= MATCH (x)-[r:R]->(y) WITH x, x.prop as prop, r ...

    // LHS
    val lhsLeaf = Argument()(solved)

    // RHS
    val labelScan = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val expand = Expand(labelScan, x, SemanticDirection.INCOMING, Seq.empty, y, r, ExpandAll)(solved)
    val projectionExpressions = Map(
      "x" -> varFor("x"),
      "prop" -> prop("x", "prop"),
      "r" -> varFor("r")
    )
    val rhsProjection = Projection(expand, projectionExpressions)(solved)

    // RollUpApply(LHS, RHS, ...)
    val rollUp =
      RollUpApply(lhsLeaf, rhsProjection, "c", "x", nullableVariables = Set("r", "y"))(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(rollUp, semanticTable).slotConfigurations

    // then
    allocations should have size 5
    allocations(rollUp.id) should equal(
      SlotConfiguration(Map(
        "c" -> RefSlot(0, nullable = false, CTList(CTAny))
      ), numberOfLongs = 0, numberOfReferences = 1)
    )
  }

  test("should handle UNION of two primitive nodes") {
    // given
    val lhs = AllNodesScan(x, Set.empty)(solved)
    val rhs = AllNodesScan(x, Set.empty)(solved)
    val plan = Union(lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(plan, semanticTable).slotConfigurations

    // then
    allocations should have size 3
    allocations(plan.id) should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), 1, 0))
  }

  test("should handle UNION of one primitive relationship and one node") {
    // given MATCH (y)<-[x]-(z) UNION MATCH (x) (sort of)
    val allNodesScan = AllNodesScan(y, Set.empty)(solved)
    val lhs = Expand(allNodesScan, y, SemanticDirection.INCOMING, Seq.empty, z, x, ExpandAll)(solved)
    val rhs = AllNodesScan(x, Set.empty)(solved)
    val plan = Union(lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(plan, semanticTable).slotConfigurations

    // then
    allocations should have size 4
    allocations(plan.id) should equal(
      SlotConfiguration(Map("x" -> RefSlot(0, nullable = false, CTAny)), 0, 1))
  }

  test("should handle UNION of projected variables") {
    val allNodesScan = AllNodesScan(x, Set.empty)(solved)
    val lhs = Projection(allNodesScan, Map("A" -> varFor("x")))(solved)
    val rhs = Projection(Argument()(solved), Map("A" -> literalInt(42)))(solved)
    val plan = Union(lhs, rhs)(solved)

    // when
    val allocations = SlotAllocation.allocateSlots(plan, semanticTable).slotConfigurations

    // then
    allocations should have size 5
    allocations(plan.id) should equal(
      SlotConfiguration(Map("A" -> RefSlot(0, nullable = true, CTAny)), 0, 1))
  }

  test("should handle nested plan expression") {
    val nestedPlan = AllNodesScan(x, Set.empty)(solved)
    val nestedProjection = Expression
    val argument = Argument()(solved)
    val plan = Projection(argument, Map("z" -> NestedPlanExpression(nestedPlan, StringLiteral("foo")(pos))(pos)))(solved)

    // when
    val physicalPlan = SlotAllocation.allocateSlots(plan, semanticTable)
    val allocations = physicalPlan.slotConfigurations

    // then
    allocations should have size 3
    allocations(plan.id) should equal(
      SlotConfiguration(Map("z" -> RefSlot(0, nullable = true, CTAny)), 0, 1)
    )
    allocations(argument.id) should equal(allocations(plan.id))
    allocations(nestedPlan.id) should equal(
      SlotConfiguration(Map("x" -> LongSlot(0, nullable = false, CTNode)), 1, 0)
    )
  }

  test("foreach allocates on left hand side with integer list") {
    // given
    val lhs = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val label = LabelToken("label2", LabelId(0))
    val argument = Argument()(solved)
    val list = literalIntList(1, 2, 3)
    val rhs = CreateNode(argument, z, Seq.empty, None)(solved)
    val foreach = ForeachApply(lhs, rhs, "i", list)(solved)

    val semanticTableWithList = SemanticTable(ASTAnnotationMap(list -> ExpressionTypeInfo(ListType(CTInteger), Some(ListType(CTAny)))))

    // when
    val allocations = SlotAllocation.allocateSlots(foreach, semanticTableWithList).slotConfigurations

    // then
    allocations should have size 4

    val lhsSlots = allocations(lhs.id)
    lhsSlots should equal(
      SlotConfiguration.empty
        .newLong("x", nullable = false, CTNode)
        .newReference("i", nullable = true, CTAny)
    )

    val rhsSlots = allocations(rhs.id)
    rhsSlots should equal(
      SlotConfiguration.empty
        .newLong("x", nullable = false, CTNode)
        .newLong("z", nullable = false, CTNode)
        .newReference("i", nullable = true, CTAny)
    )

    allocations(foreach.id) shouldBe theSameInstanceAs(lhsSlots)
  }

  test("foreach allocates on left hand side with node list") {
    // given
    val lhs = NodeByLabelScan(x, LABEL, Set.empty)(solved)
    val label = LabelToken("label2", LabelId(0))
    val argument = Argument()(solved)
    val list = literalList(Variable("x")(pos))
    val rhs = CreateNode(argument, z, Seq.empty, None)(solved)
    val foreach = ForeachApply(lhs, rhs, "i", list)(solved)

    val semanticTableWithList = SemanticTable(ASTAnnotationMap(list -> ExpressionTypeInfo(ListType(CTNode), Some(ListType(CTNode)))))

    // when
    val allocations = SlotAllocation.allocateSlots(foreach, semanticTableWithList).slotConfigurations

    // then
    allocations should have size 4

    val lhsSlots = allocations(lhs.id)
    lhsSlots should equal(
      SlotConfiguration.empty
        .newLong("x", nullable = false, CTNode)
        .newLong("i", nullable = true, CTNode)
    )

    val rhsSlots = allocations(rhs.id)
    rhsSlots should equal(
      SlotConfiguration.empty
        .newLong("x", nullable = false, CTNode)
        .newLong("i", nullable = true, CTNode)
        .newLong("z", nullable = false, CTNode)
    )

    allocations(foreach.id) shouldBe theSameInstanceAs(lhsSlots)
  }
}