package com.prisma.api.mutations.nonEmbedded.nestedMutations

import com.prisma.api.ApiSpecBase
import com.prisma.shared.models.ConnectorCapability
import com.prisma.shared.models.ConnectorCapability.JoinRelationLinksCapability
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}

class NestedConnectMutationInsideUpdateSpec extends FlatSpec with Matchers with ApiSpecBase with SchemaBase {
  override def runOnlyForCapabilities: Set[ConnectorCapability] = Set(JoinRelationLinksCapability)

  "a P1! to C1! relation with the child already in a relation" should "error when connecting by id since old required parent relation would be broken" in {
    val project = SchemaDsl.fromString() { schemaP1reqToC1req }
    database.setup(project)

    val child1Id = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childReq: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |    childReq{
          |       id
          |    }
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.childReq.id")

    val parentId2 = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p2"
          |    childReq: {
          |      create: {c: "c2"}
          |    }
          |  }){
          |  id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    server.queryThatMustFail(
      s"""
         |mutation {
         |  updateParent(
         |  where: {id: "$parentId2"}
         |  data:{
         |    p: "p2"
         |    childReq: {connect: {id: "$child1Id"}}
         |  }){
         |    childReq {
         |      c
         |    }
         |  }
         |}
      """,
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'ChildToParent' between Child and Parent"
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "a P1! to C1 relation with the child already in a relation" should "should fail on existing old parent" in {
    val project = SchemaDsl.fromString() { schemaP1reqToC1opt }
    database.setup(project)

    val child1Id = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childReq: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |    childReq{
          |       id
          |    }
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.childReq.id")

    val parentId2 = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p2"
          |    childReq: {
          |      create: {c: "c2"}
          |    }
          |  }){
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    server.queryThatMustFail(
      s"""
         |mutation {
         |  updateParent(
         |  where: {id: "$parentId2"}
         |  data:{
         |    p: "p2"
         |    childReq: {connect: {id: "$child1Id"}}
         |  }){
         |    childReq {
         |      c
         |    }
         |  }
         |}
      """,
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'ChildToParent' between Child and Parent"
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "a P1! to C1  relation with the child not in a relation" should "be connectable through a nested mutation by id" in {
    val project = SchemaDsl.fromString() { schemaP1reqToC1opt }
    database.setup(project)

    val looseChildId = server
      .query(
        """mutation {
          |  createChild(data: {c: "looseChild"})
          |  {
          |    id
          |  }
          |}""".stripMargin,
        project
      )
      .pathAsString("data.createChild.id")

    val otherParentWithChildId = server
      .query(
        s"""
           |mutation {
           |  createParent(data:{
           |    p: "otherParent"
           |    childReq: {create: {c: "otherChild"}}
           |  }){
           |    id
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.createParent.id")

    val child1Id = server
      .query(
        """mutation {
          |  createChild(data: {c: "c1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createChild.id")

    val parentId = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p2"
          |    childReq: {
          |      create: {c: "c2"}
          |    }
          |  }){
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {id: "$parentId"}
         |  data:{
         |    p: "p2"
         |    childReq: {connect: {id: "$child1Id"}}
         |  }){
         |    childReq {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childReq":{"c":"c1"}}}}""")
    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    // verify preexisting data

    server
      .query(
        s"""
           |{
           |  parent(where: {id: "${otherParentWithChildId}"}){
           |    childReq {
           |      c
           |    }
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.parent.childReq.c") should be("otherChild")

    server
      .query(
        s"""
           |{
           |  child(where: {id: "${looseChildId}"}){
           |    c
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.child.c") should be("looseChild")
  }

  "a P1 to C1  relation with the child already in a relation" should "be connectable through a nested mutation by id if the child is already in a relation" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1opt }
    database.setup(project)

    val looseChildId = server
      .query(
        """mutation {
          |  createChild(data: {c: "looseChild"})
          |  {
          |    id
          |  }
          |}""".stripMargin,
        project
      )
      .pathAsString("data.createChild.id")

    val otherParentWithChildId = server
      .query(
        s"""
           |mutation {
           |  createParent(data:{
           |    p: "otherParent"
           |    childOpt: {create: {c: "otherChild"}}
           |  }){
           |    id
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.createParent.id")

    val child1Id = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childOpt: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |    childOpt{
          |       id
          |    }
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.childOpt.id")

    val parentId2 = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p2"
          |    childOpt: {
          |      create: {c: "c2"}
          |    }
          |  }){
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(3) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parentId2"}
         |  data:{
         |    p: "p2"
         |    childOpt: {connect: {id: "$child1Id"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    // verify preexisting data

    server
      .query(
        s"""
           |{
           |  parent(where: {id: "${otherParentWithChildId}"}){
           |    childOpt {
           |      c
           |    }
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.parent.childOpt.c") should be("otherChild")

    server
      .query(
        s"""
           |{
           |  child(where: {id: "${looseChildId}"}){
           |    c
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.child.c") should be("looseChild")
  }

  "a P1 to C1  relation with the child and the parent without a relation" should "be connectable through a nested mutation by id" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1opt }
    database.setup(project)

    val child1Id = server
      .query(
        """mutation {
          |  createChild(data: {c: "c1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createChild.id")

    val parent1Id = server
      .query(
        """mutation {
          |  createParent(data: {p: "p1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(0) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parent1Id"}
         |  data:{
         |    p: "p2"
         |    childOpt: {connect: {id: "$child1Id"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1 to C1  relation with the child without a relation" should "be connectable through a nested mutation by id" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1opt }
    database.setup(project)

    val child1Id = server
      .query(
        """mutation {
          |  createChild(data: {c: "c1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createChild.id")

    val parentId = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childOpt: {
          |      create: {c: "c2"}
          |    }
          |  }){
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parentId"}
         |  data:{
         |    p: "p2"
         |    childOpt: {connect: {id: "$child1Id"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1 to C1  relation with the parent without a relation" should "be connectable through a nested mutation by id" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1opt }
    database.setup(project)

    val parentId = server
      .query(
        """mutation {
          |  createParent(data: {p: "p1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    val childId = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p2"
          |    childOpt: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |    childOpt{id}
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.childOpt.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parentId"}
         |  data:{
         |    childOpt: {connect: {id: "$childId"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a PM to C1!  relation with the child already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaPMToC1req }
    database.setup(project)

    val otherParentWithChildId = server
      .query(
        s"""
           |mutation {
           |  createParent(data:{
           |    p: "otherParent"
           |    childrenOpt: {create: {c: "otherChild"}}
           |  }){
           |    id
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.createParent.id")

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childrenOpt: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childrenOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p2"
        |    childrenOpt: {
        |      create: {c: "c2"}
        |    }
        |  }){
        |    childrenOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(3) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |    where: {p: "p2"}
         |    data:{
         |    childrenOpt: {connect: {c: "c1"}}
         |  }){
         |    childrenOpt(first:10) {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"},{"c":"c2"}]}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(3) }

    // verify preexisting data

    server
      .query(
        s"""
           |{
           |  parent(where: {id: "${otherParentWithChildId}"}){
           |    childrenOpt {
           |      c
           |    }
           |  }
           |}
      """.stripMargin,
        project
      )
      .pathAsString("data.parent.childrenOpt.[0].c") should be("otherChild")
  }

  "a P1 to C1!  relation with the child and the parent already in a relation" should "should error in a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1req }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childOpt: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p2"
        |    childOpt: {
        |      create: {c: "c2"}
        |    }
        |  }){
        |    childOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    server.queryThatMustFail(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p2"} 
         |  data:{
         |    childOpt: {connect: {c: "c1"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'ChildToParent' between Child and Parent"
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "a P1 to C1! relation with the child already in a relation" should "should not error when switching to a different parent" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1req }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childOpt: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p2"
        |  }){
        |  p
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p2"} 
         |  data:{
         |    childOpt: {connect: {c: "c1"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a PM to C1  relation with the child already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaPMToC1opt }
    database.setup(project)

    server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childrenOpt: {
          |      create: [{c: "c1"}, {c: "c2"}]
          |    }
          |  }){
          |    childrenOpt{
          |       c
          |    }
          |  }
          |}""",
        project
      )

    server
      .query(
        """mutation {
          |  createParent(data: {p: "p2"}){
          |    p
          |  }
          |}""",
        project
      )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    // we are even resilient against multiple identical connects here -> twice connecting to c2

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: { p: "p2"}
         |  data:{
         |    childrenOpt: {connect: [{c: "c1"},{c: "c2"},{c: "c2"}]}
         |  }){
         |    childrenOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"},{"c":"c2"}]}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "a PM to C1  relation with the child without a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaPMToC1opt }
    database.setup(project)

    server
      .query(
        """mutation {
          |  createChild(data: {c: "c1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )

    server
      .query(
        """mutation {
          |  createParent(data: {p: "p1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(0) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p:"p1"}
         |  data:{
         |    childrenOpt: {connect: {c: "c1"}}
         |  }){
         |    childrenOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"}]}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1! to CM  relation with the child already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1reqToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childReq: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childReq{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p2"
        |    childReq: {
        |      create: {c: "c2"}
        |    }
        |  }){
        |    childReq{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p2"}
         |  data:{
         |    childReq: {connect: {c: "c1"}}
         |  }){
         |    childReq {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childReq":{"c":"c1"}}}}""")

    server.query(s"""query{children{c, parentsOpt{p}}}""", project).toString should be(
      """{"data":{"children":[{"c":"c1","parentsOpt":[{"p":"p1"},{"p":"p2"}]},{"c":"c2","parentsOpt":[]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "a P1! to CM  relation with the child not already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1reqToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createChild(data: {c: "c1"}){
        |       c
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p2"
        |    childReq: {
        |      create: {c: "c2"}
        |    }
        |  }){
        |    childReq{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p2"}
         |  data:{
         |    childReq: {connect: {c: "c1"}}
         |  }){
         |    childReq {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childReq":{"c":"c1"}}}}""")

    server.query(s"""query{children{c, parentsOpt{p}}}""", project).toString should be(
      """{"data":{"children":[{"c":"c1","parentsOpt":[{"p":"p2"}]},{"c":"c2","parentsOpt":[]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1 to CM  relation with the child already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1optToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childOpt: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {p: "p2"}){
        |    p
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |    where: {p: "p2"}
         |    data:{
         |    childOpt: {connect: {c: "c1"}}
         |  }){
         |    childOpt{
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    server.query(s"""query{children{c, parentsOpt{p}}}""", project).toString should be(
      """{"data":{"children":[{"c":"c1","parentsOpt":[{"p":"p1"},{"p":"p2"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "a P1 to CM  relation with the child not already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1optToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createChild(data: {c: "c1"}){
        |       c
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {p: "p1"}){
        |       p
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(0) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p1"}
         |  data:{
         |    childOpt: {connect: {c: "c1"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    server.query(s"""query{children{c, parentsOpt{p}}}""", project).toString should be("""{"data":{"children":[{"c":"c1","parentsOpt":[{"p":"p1"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a PM to CM  relation with the children already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaPMToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childrenOpt: {
        |      create: [{c: "c1"},{c: "c2"}]
        |    }
        |  }){
        |    childrenOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p2"
        |    childrenOpt: {
        |      create: [{c: "c3"},{c: "c4"}]
        |    }
        |  }){
        |    childrenOpt{
        |       c
        |    }
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(4) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {    p: "p2"}
         |  data:{
         |    childrenOpt: {connect: [{c: "c1"}, {c: "c2"}]}
         |  }){
         |    childrenOpt{
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"},{"c":"c2"},{"c":"c3"},{"c":"c4"}]}}}""")

    server.query(s"""query{children{c, parentsOpt{p}}}""", project).toString should be(
      """{"data":{"children":[{"c":"c1","parentsOpt":[{"p":"p1"},{"p":"p2"}]},{"c":"c2","parentsOpt":[{"p":"p1"},{"p":"p2"}]},{"c":"c3","parentsOpt":[{"p":"p2"}]},{"c":"c4","parentsOpt":[{"p":"p2"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(6) }
  }

  "a PM to CM  relation with the child not already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaPMToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createChild(data: {c: "c1"}){
        |       c
        |  }
        |}""",
      project
    )

    server.query(
      """mutation {
        |  createParent(data: {p: "p1"}){
        |       p
        |  }
        |}""",
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(0) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p1"}
         |  data:{
         |    childrenOpt: {connect: {c: "c1"}}
         |  }){
         |    childrenOpt {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"}]}}}""")

    server.query(s"""query{children{parentsOpt{p}}}""", project).toString should be("""{"data":{"children":[{"parentsOpt":[{"p":"p1"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a one to many relation" should "be connectable by id through a nested mutation" in {
    val project = SchemaDsl.fromString() {
      """type Comment {
        | id: ID! @unique
        | text: String
        | todo: Todo
        |}
        |
        |type Todo {
        | id: ID! @unique
        | comments: [Comment]
        |}
      """.stripMargin
    }
    database.setup(project)

    val todoId     = server.query("""mutation { createTodo(data: {}){ id } }""", project).pathAsString("data.createTodo.id")
    val comment1Id = server.query("""mutation { createComment(data: {text: "comment1"}){ id } }""", project).pathAsString("data.createComment.id")
    val comment2Id = server.query("""mutation { createComment(data: {text: "comment2"}){ id } }""", project).pathAsString("data.createComment.id")

    val result = server.query(
      s"""mutation {
         |  updateTodo(
         |    where: {
         |      id: "$todoId"
         |    }
         |    data:{
         |      comments: {
         |        connect: [{id: "$comment1Id"}, {id: "$comment2Id"}]
         |      }
         |    }
         |  ){
         |    comments {
         |      text
         |    }
         |  }
         |}
      """,
      project
    )

    mustBeEqual(result.pathAsJsValue("data.updateTodo.comments").toString, """[{"text":"comment1"},{"text":"comment2"}]""")
  }

  "a one to many relation" should "be connectable by any unique argument through a nested mutation" in {
    val project = SchemaDsl.fromString() {
      """type Comment {
        | id: ID! @unique
        | text: String
        | alias: String! @unique
        | todo: Todo
        |}
        |
        |type Todo {
        | id: ID! @unique
        | comments: [Comment]
        |}
      """.stripMargin
    }
    database.setup(project)

    val todoId = server.query("""mutation { createTodo(data: {}){ id } }""", project).pathAsString("data.createTodo.id")
    server.query("""mutation { createComment(data: {text: "comment1", alias: "alias1"}){ id } }""", project).pathAsString("data.createComment.id")
    server.query("""mutation { createComment(data: {text: "comment2", alias: "alias2"}){ id } }""", project).pathAsString("data.createComment.id")

    val result = server.query(
      s"""mutation {
         |  updateTodo(
         |    where: {
         |      id: "$todoId"
         |    }
         |    data:{
         |      comments: {
         |        connect: [{alias: "alias1"}, {alias: "alias2"}]
         |      }
         |    }
         |  ){
         |    comments {
         |      text
         |    }
         |  }
         |}
      """,
      project
    )

    mustBeEqual(result.pathAsJsValue("data.updateTodo.comments").toString, """[{"text":"comment1"},{"text":"comment2"}]""")
  }

  "a one to many relation" should "be connectable by id through a nested mutation 2" in {
    val project = SchemaDsl.fromString() {
      """type Comment {
        | id: ID! @unique
        | text: String
        | todo: Todo
        |}
        |
        |type Todo {
        | id: ID! @unique
        | title: String!
        | comments: [Comment]
        |}
      """.stripMargin
    }
    database.setup(project)

    val commentId = server.query("""mutation { createComment(data: {}){ id } }""", project).pathAsString("data.createComment.id")
    val todoId    = server.query("""mutation { createTodo(data: { title: "the title" }){ id } }""", project).pathAsString("data.createTodo.id")

    val result = server.query(
      s"""
         |mutation {
         |  updateComment(
         |    where: {
         |      id: "$commentId"
         |    }
         |    data: {
         |      todo: {
         |        connect: {id: "$todoId"}
         |      }
         |    }
         |  ){
         |    id
         |    todo {
         |      title
         |    }
         |  }
         |}
      """,
      project
    )
    mustBeEqual(result.pathAsString("data.updateComment.todo.title"), "the title")
  }

  "a one to one relation" should "be connectable by id through a nested mutation" in {
    val project = SchemaDsl.fromString() {
      """type Note {
        | id: ID! @unique
        | text: String
        | todo: Todo
        |}
        |
        |type Todo {
        | id: ID! @unique
        | title: String!
        | note: Note
        |}
      """.stripMargin
    }
    database.setup(project)

    val noteId = server.query("""mutation { createNote(data: {}){ id } }""", project).pathAsString("data.createNote.id")
    val todoId = server.query("""mutation { createTodo(data: { title: "the title" }){ id } }""", project).pathAsString("data.createTodo.id")

    val result = server.query(
      s"""
         |mutation {
         |  updateNote(
         |    where: {
         |      id: "$noteId"
         |    }
         |    data: {
         |      todo: {
         |        connect: {id: "$todoId"}
         |      }
         |    }
         |  ){
         |    id
         |    todo {
         |      title
         |    }
         |  }
         |}
      """,
      project
    )
    mustBeEqual(result.pathAsString("data.updateNote.todo.title"), "the title")
  }

  "A one to one relation" should "connecting nodes by id through a nested mutation should not error when items are already connected" in {
    val project = SchemaDsl.fromString() {
      """type Note {
        | id: ID! @unique
        | text: String
        | todo: Todo
        |}
        |
        |type Todo {
        | id: ID! @unique
        | title: String!
        | note: Note
        |}
      """.stripMargin
    }
    database.setup(project)

    val noteId = server.query("""mutation { createNote(data: {}){ id } }""", project).pathAsString("data.createNote.id")
    val todoId = server.query("""mutation { createTodo(data: { title: "the title" }){ id } }""", project).pathAsString("data.createTodo.id")

    val result = server.query(
      s"""
         |mutation {
         |  updateNote(
         |    where: {
         |      id: "$noteId"
         |    }
         |    data: {
         |      todo: {
         |        connect: {id: "$todoId"}
         |      }
         |    }
         |  ){
         |    id
         |    todo {
         |      title
         |    }
         |  }
         |}
      """,
      project
    )
    mustBeEqual(result.pathAsString("data.updateNote.todo.title"), "the title")

    server.query(
      s"""
         |mutation {
         |  updateNote(
         |    where: {
         |      id: "$noteId"
         |    }
         |    data: {
         |      todo: {
         |        connect: {id: "$todoId"}
         |      }
         |    }
         |  ){
         |    id
         |    todo {
         |      title
         |    }
         |  }
         |}
      """,
      project
    )
  }

  "a one to many relation" should "be connectable by unique through a nested mutation" in {
    val project = SchemaDsl.fromString() {
      """type Comment {
        | id: ID! @unique
        | text: String @unique
        | todo: Todo
        |}
        |
        |type Todo {
        | id: ID! @unique
        | title: String @unique
        | comments: [Comment]
        |}
      """.stripMargin
    }
    database.setup(project)

    server.query("""mutation { createTodo(data: {title: "todo"}){ id } }""", project).pathAsString("data.createTodo.id")
    server.query("""mutation { createComment(data: {text: "comment1"}){ id } }""", project).pathAsString("data.createComment.id")
    server.query("""mutation { createComment(data: {text: "comment2"}){ id } }""", project).pathAsString("data.createComment.id")

    val result = server.query(
      s"""mutation {
         |  updateTodo(
         |    where: {
         |      title: "todo"
         |    }
         |    data:{
         |      comments: {
         |        connect: [{text: "comment1"}, {text: "comment2"}]
         |      }
         |    }
         |  ){
         |    comments {
         |      text
         |    }
         |  }
         |}
      """,
      project
    )

    mustBeEqual(result.pathAsJsValue("data.updateTodo.comments").toString, """[{"text":"comment1"},{"text":"comment2"}]""")
  }

  "a PM to CM  self relation with the child not already in a relation" should "be connectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() {
      """type Technology {
        | id: ID! @unique
        | name: String! @unique
        | childTechnologies: [Technology] @relation(name: "ChildTechnologies")
        | parentTechnologies: [Technology] @relation(name: "ChildTechnologies")
        |}
      """.stripMargin
    }
    database.setup(project)

    server.query("""mutation {createTechnology(data: {name: "techA"}){name}}""", project)

    server.query("""mutation {createTechnology(data: {name: "techB"}){name}}""", project)

    val res = server.query(
      s"""mutation {
         |  updateTechnology(where: {name: "techA"},
         |                   data:  {childTechnologies: {connect: {name: "techB"}}})
         |      {name,
         |       childTechnologies  {name}
         |       parentTechnologies {name}}
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateTechnology":{"name":"techA","childTechnologies":[{"name":"techB"}],"parentTechnologies":[]}}}""")

    val res2 = server.query(
      s"""query {
         |  technologies{
         |       name
         |       childTechnologies  {name}
         |       parentTechnologies {name}
         |  }
         |}
      """,
      project
    )

    res2.toString should be(
      """{"data":{"technologies":[{"name":"techA","childTechnologies":[{"name":"techB"}],"parentTechnologies":[]},{"name":"techB","childTechnologies":[],"parentTechnologies":[{"name":"techA"}]}]}}""")
  }

  "Connecting two nodes twice" should "not error" in {
    val project = SchemaDsl.fromString() {
      """type Child {
        | id: ID! @unique
        | c: String! @unique
        | parents: [Parent]
        |}
        |
        |type Parent {
        | id: ID! @unique
        | p: String! @unique
        | children: [Child]
        |}
      """.stripMargin
    }
    database.setup(project)

    val parentId = server
      .query(
        """mutation {
          |  createParent(data: {p: "p1"})
          |  {
          |    id
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.id")

    val childId = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p2"
          |    children: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |    children{id}
          |  }
          |}""",
        project
      )
      .pathAsString("data.createParent.children.[0].id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parentId"}
         |  data:{
         |    children: {connect: {id: "$childId"}}
         |  }){
         |    children {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"children":[{"c":"c1"}]}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parentId"}
         |  data:{
         |    children: {connect: {id: "$childId"}}
         |  }){
         |    children {
         |      c
         |    }
         |  }
         |}
      """,
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "Connecting several times" should "not error and only connect the item once" in {

    val project = SchemaDsl.fromString() {
      """
        |type Post {
        |  id: ID! @unique
        |  authors: [AUser]
        |  title: String! @unique
        |}
        |
        |type AUser {
        |  id: ID! @unique
        |  name: String! @unique
        |  posts: [Post]
        |}"""
    }

    database.setup(project)

    server.query(s""" mutation {createPost(data: {title:"Title"}) {title}} """, project)
    server.query(s""" mutation {createAUser(data: {name:"Author"}) {name}} """, project)

    server.query(s""" mutation {updateAUser(where: { name: "Author"}, data:{posts:{connect:{title: "Title"}}}) {name}} """, project)
    server.query(s""" mutation {updateAUser(where: { name: "Author"}, data:{posts:{connect:{title: "Title"}}}) {name}} """, project)
    server.query(s""" mutation {updateAUser(where: { name: "Author"}, data:{posts:{connect:{title: "Title"}}}) {name}} """, project)

    server.query("""query{aUsers{name, posts{title}}}""", project).toString should be("""{"data":{"aUsers":[{"name":"Author","posts":[{"title":"Title"}]}]}}""")
  }

}
