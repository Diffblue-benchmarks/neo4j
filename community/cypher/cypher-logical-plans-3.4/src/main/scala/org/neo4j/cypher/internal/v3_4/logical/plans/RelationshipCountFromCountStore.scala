/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.v3_4.logical.plans

import org.neo4j.cypher.internal.ir.v3_4.{CardinalityEstimation, IdName, PlannerQuery}
import org.neo4j.cypher.internal.v3_4.expressions.{LabelName, RelTypeName}

/**
  * Produce a single row with the contents of argument and a new value 'idName'. For each
  * relationship type in 'typeNames', the number of relationship matching
  *
  *   (:startLabel)-[:type]->(:endLabel)
  *
  * is fetched from the counts store. These counts are summed, and the result is
  * assigned to 'idName'.
  */
case class RelationshipCountFromCountStore(
                                            idName: IdName,
                                            startLabel: Option[LabelName],
                                            typeNames: Seq[RelTypeName],
                                            endLabel: Option[LabelName],
                                            argumentIds: Set[IdName]
                                          )(val solved: PlannerQuery with CardinalityEstimation)
  extends LogicalLeafPlan {

  def availableSymbols = Set(idName)
}