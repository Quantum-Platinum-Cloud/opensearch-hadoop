/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */
 
/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.opensearch.spark.integration

import java.lang.Boolean.{FALSE, TRUE}
import java.{lang => jl}
import java.sql.Timestamp
import java.{util => ju}
import java.util.concurrent.TimeUnit
import scala.collection.JavaConversions.propertiesAsScalaMap
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.Map
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkException
import org.apache.spark.sql.Row
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.types.ArrayType
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.MapType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.types.Decimal
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.sql.types.TimestampType
import org.apache.spark.storage.StorageLevel._
import org.opensearch.hadoop.cfg.ConfigurationOptions._
import org.opensearch.spark._
import org.opensearch.spark.cfg._
import org.opensearch.spark.sql._
import org.opensearch.spark.sql.sqlContextFunctions
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.is
import org.hamcrest.Matchers.not
import org.junit.{AfterClass, BeforeClass, ClassRule, FixMethodOrder, Ignore, Test}
import org.junit.Assert._
import org.junit.Assume._
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.junit.runners.MethodSorters
import com.esotericsoftware.kryo.io.{Input => KryoInput}
import com.esotericsoftware.kryo.io.{Output => KryoOutput}
import org.apache.spark.rdd.RDD

import javax.xml.bind.DatatypeConverter
import org.apache.spark.sql.types.DoubleType
import org.opensearch.hadoop.rest.RestUtils
import org.opensearch.hadoop.{OpenSearchAssume, OpenSearchHadoopIllegalArgumentException, OpenSearchHadoopIllegalStateException, TestData}
import org.opensearch.hadoop.util.{OpenSearchMajorVersion, StringUtils, TestSettings, TestUtils}
import org.opensearch.spark.sql.{OpenSearchSparkSQL, SchemaUtilsTestable}
import org.opensearch.spark.sql.api.java.JavaOpenSearchSparkSQL

object AbstractScalaOpenSearchScalaSparkSQL {
  @transient val conf = new SparkConf().set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .setMaster("local").setAppName("opensearchtest").set("spark.executor.extraJavaOptions", "-XX:MaxPermSize=256m")
    .set("spark.io.compression.codec", "lz4")
    .setJars(SparkUtils.OPENSEARCH_SPARK_TESTING_JAR)
  @transient var cfg: SparkConf = null
  @transient var sc: SparkContext = null
  @transient var sqc: SQLContext = null

  @transient var keywordType: String = "keyword"
  @transient var textType: String = "text"

  @transient @ClassRule val testData = new TestData()

  @BeforeClass
  def setup() {
    conf.setAll(TestSettings.TESTING_PROPS);
    sc = new SparkContext(conf)
    sqc = new SQLContext(sc)
  }

  @AfterClass
  def cleanup() {
    if (sc != null) {
      sc.stop
      // give jetty time to clean its act up
      Thread.sleep(TimeUnit.SECONDS.toMillis(3))
    }
  }

  @Parameters
  def testParams(): ju.Collection[Array[jl.Object]] = {
    val list = new ju.ArrayList[Array[jl.Object]]()

    val noQuery = ""
    val uriQuery = "?q=*"
    val dslQuery = """ {"query" : { "match_all" : { } } } """

    // no query                      meta, push, strict, filter, encode, query
    list.add(Array("default",        FALSE, TRUE,  FALSE, TRUE, FALSE, noQuery))  // 0
    list.add(Array("defaultstrict",  FALSE, TRUE,  TRUE,  TRUE, FALSE, noQuery))  // 1
    list.add(Array("defaultnopush",  FALSE, FALSE, FALSE, TRUE, FALSE, noQuery))  // 2
    list.add(Array("withmeta",       TRUE,  TRUE,  FALSE, TRUE, FALSE, noQuery))  // 3
    list.add(Array("withmetastrict", TRUE,  TRUE,  TRUE,  TRUE, FALSE, noQuery))  // 4
    list.add(Array("withmetanopush", TRUE,  FALSE, FALSE, TRUE, FALSE, noQuery))  // 5

    // disable double filtering                  meta, push, strict, filter, encode, query
    list.add(Array("default_skiphandled",        FALSE, TRUE,  FALSE, FALSE, FALSE, noQuery))  // 6
    list.add(Array("defaultstrict_skiphandled",  FALSE, TRUE,  TRUE,  FALSE, FALSE, noQuery))  // 7
    list.add(Array("defaultnopush_skiphandled",  FALSE, FALSE, FALSE, FALSE, FALSE, noQuery))  // 8
    list.add(Array("withmeta_skiphandled",       TRUE,  TRUE,  FALSE, FALSE, FALSE, noQuery))  // 9
    list.add(Array("withmetastrict_skiphandled", TRUE,  TRUE,  TRUE,  FALSE, FALSE, noQuery))  // 10
    list.add(Array("withmetanopush_skiphandled", TRUE,  FALSE, FALSE, FALSE, FALSE, noQuery))  // 11

    // uri query                              meta, push, strict, filter, encode, query
    list.add(Array("defaulturiquery",         FALSE, TRUE,  FALSE, TRUE, FALSE, uriQuery))  // 12
    list.add(Array("defaulturiquerystrict",   FALSE, TRUE,  TRUE,  TRUE, FALSE, uriQuery))  // 13
    list.add(Array("defaulturiquerynopush",   FALSE, FALSE, FALSE, TRUE, FALSE, uriQuery))  // 14
    list.add(Array("withmetauri_query",       TRUE,  TRUE,  FALSE, TRUE, FALSE, uriQuery))  // 15
    list.add(Array("withmetauri_querystrict", TRUE,  TRUE,  TRUE,  TRUE, FALSE, uriQuery))  // 16
    list.add(Array("withmetauri_querynopush", TRUE,  FALSE, FALSE, TRUE, FALSE, uriQuery))  // 17

    // disable double filtering                           meta, push, strict, filter, encode, query
    list.add(Array("defaulturiquery_skiphandled",         FALSE, TRUE,  FALSE, FALSE, FALSE, uriQuery))  // 18
    list.add(Array("defaulturiquerystrict_skiphandled",   FALSE, TRUE,  TRUE,  FALSE, FALSE, uriQuery))  // 19
    list.add(Array("defaulturiquerynopush_skiphandled",   FALSE, FALSE, FALSE, FALSE, FALSE, uriQuery))  // 20
    list.add(Array("withmetauri_query_skiphandled",       TRUE,  TRUE,  FALSE, FALSE, FALSE, uriQuery))  // 21
    list.add(Array("withmetauri_querystrict_skiphandled", TRUE,  TRUE,  TRUE,  FALSE, FALSE, uriQuery))  // 22
    list.add(Array("withmetauri_querynopush_skiphandled", TRUE,  FALSE, FALSE, FALSE, FALSE, uriQuery))  // 23

    // dsl query                             meta, push, strict, filter, encode, query
    list.add(Array("defaultdslquery",        FALSE, TRUE,  FALSE, TRUE, FALSE, dslQuery))  // 24
    list.add(Array("defaultstrictdslquery",  FALSE, TRUE,  TRUE,  TRUE, FALSE, dslQuery))  // 25
    list.add(Array("defaultnopushdslquery",  FALSE, FALSE, FALSE, TRUE, FALSE, dslQuery))  // 26
    list.add(Array("withmetadslquery",       TRUE,  TRUE,  FALSE, TRUE, FALSE, dslQuery))  // 27
    list.add(Array("withmetastrictdslquery", TRUE,  TRUE,  TRUE,  TRUE, FALSE, dslQuery))  // 28
    list.add(Array("withmetanopushdslquery", TRUE,  FALSE, FALSE, TRUE, FALSE, dslQuery))  // 29

    // disable double filtering                          meta, push, strict, filter, encode, query
    list.add(Array("defaultdslquery_skiphandled",        FALSE, TRUE,  FALSE, FALSE, FALSE, dslQuery))  // 30
    list.add(Array("defaultstrictdslquery_skiphandled",  FALSE, TRUE,  TRUE,  FALSE, FALSE, dslQuery))  // 31
    list.add(Array("defaultnopushdslquery_skiphandled",  FALSE, FALSE, FALSE, FALSE, FALSE, dslQuery))  // 32
    list.add(Array("withmetadslquery_skiphandled",       TRUE,  TRUE,  FALSE, FALSE, FALSE, dslQuery))  // 33
    list.add(Array("withmetastrictdslquery_skiphandled", TRUE,  TRUE,  TRUE,  FALSE, FALSE, dslQuery))  // 34
    list.add(Array("withmetanopushdslquery_skiphandled", TRUE,  FALSE, FALSE, FALSE, FALSE, dslQuery))  // 35

    // unicode                                      meta, push, strict, filter, encode, query
    list.add(Array("default_" + "בְּדִיק" + "_",        FALSE, TRUE,  FALSE, TRUE, TRUE, noQuery))  // 36
    list.add(Array("defaultstrict_" + "בְּדִיק" + "_",  FALSE, TRUE,  TRUE,  TRUE, TRUE, noQuery))  // 37
    list.add(Array("defaultnopush_" + "בְּדִיק" + "_",  FALSE, FALSE, FALSE, TRUE, TRUE, noQuery))  // 38
    list.add(Array("withmeta_" + "בְּדִיק" + "_",       TRUE,  TRUE,  FALSE, TRUE, TRUE, noQuery))  // 39
    list.add(Array("withmetastrict_" + "בְּדִיק" + "_", TRUE,  TRUE,  TRUE,  TRUE, TRUE, noQuery))  // 40
    list.add(Array("withmetanopush_" + "בְּדִיק" + "_", TRUE,  FALSE, FALSE, TRUE, TRUE, noQuery))  // 41

    // disable double filtering                                 meta, push, strict, filter, encode, query
    list.add(Array("default_skiphandled_" + "בְּדִיק" + "_",        FALSE, TRUE,  FALSE, FALSE, TRUE, noQuery))  // 42
    list.add(Array("defaultstrict_skiphandled_" + "בְּדִיק" + "_",  FALSE, TRUE,  TRUE,  FALSE, TRUE, noQuery))  // 43
    list.add(Array("defaultnopush_skiphandled_" + "בְּדִיק" + "_",  FALSE, FALSE, FALSE, FALSE, TRUE, noQuery))  // 44
    list.add(Array("withmeta_skiphandled_" + "בְּדִיק" + "_",       TRUE,  TRUE,  FALSE, FALSE, TRUE, noQuery))  // 45
    list.add(Array("withmetastrict_skiphandled_" + "בְּדִיק" + "_", TRUE,  TRUE,  TRUE,  FALSE, TRUE, noQuery))  // 46
    list.add(Array("withmetanopush_skiphandled_" + "בְּדִיק" + "_", TRUE,  FALSE, FALSE, FALSE, TRUE, noQuery))  // 47

    list
  }
}

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(classOf[Parameterized])
class AbstractScalaOpenSearchScalaSparkSQL(prefix: String, readMetadata: jl.Boolean, pushDown: jl.Boolean, strictPushDown: jl.Boolean, doubleFiltering: jl.Boolean, escapeResources: jl.Boolean, query: String = "") extends Serializable {

  val sc = AbstractScalaOpenSearchScalaSparkSQL.sc
  val sqc = AbstractScalaOpenSearchScalaSparkSQL.sqc
  val cfg = Map(OPENSEARCH_QUERY -> query,
                OPENSEARCH_READ_METADATA -> readMetadata.toString(),
                "opensearch.internal.spark.sql.pushdown" -> pushDown.toString(),
                "opensearch.internal.spark.sql.pushdown.strict" -> strictPushDown.toString(),
                "opensearch.internal.spark.sql.pushdown.keep.handled.filters" -> doubleFiltering.toString())

  val version = TestUtils.getOpenSearchClusterInfo.getMajorVersion
  val datInput = AbstractScalaOpenSearchScalaSparkSQL.testData.sampleArtistsDatUri().toString
  val keyword = AbstractScalaOpenSearchScalaSparkSQL.keywordType
  val text = AbstractScalaOpenSearchScalaSparkSQL.textType

  @Test
  def test1KryoScalaOpenSearchRow() {
    val kryo = SparkUtils.sparkSerializer(sc.getConf)
    val row = new ScalaOpenSearchRow(new ArrayBuffer() ++= StringUtils.tokenize("foo,bar,tar").asScala)

    val storage = Array.ofDim[Byte](512)
    val output = new KryoOutput(storage)
    val input = new KryoInput(storage)

    kryo.writeClassAndObject(output, row)
    val serialized = kryo.readClassAndObject(input).asInstanceOf[ScalaOpenSearchRow]
    println(serialized.rowOrder)
  }

  @Test(expected = classOf[OpenSearchHadoopIllegalArgumentException])
  def testNoIndexExists() {
    val idx = sqc.read.format("org.opensearch.spark.sql").load("not_existing_index")
    idx.printSchema()
  }

  @Test
  def testArrayMappingFirstLevel() {
    val mapping = wrapMapping("data", s"""{
      |    "properties" : {
      |      "arr" : {
      |        "properties" : {
      |          "one" : { "type" : "$keyword" },
      |          "two" : { "type" : "$keyword" }
      |        }
      |      },
      |      "top-level" : { "type" : "$keyword" }
      |    }
      |}""".stripMargin)

    val index = wrapIndex("sparksql-test-array-mapping-top-level")
    val typed = "data"
    val (target, docEndpoint) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    // add some data
    val doc1 = """{"arr" : [{"one" : "1", "two" : "2"}, {"one" : "unu", "two" : "doi"}], "top-level" : "root" }""".stripMargin

    RestUtils.postData(docEndpoint, doc1.getBytes(StringUtils.UTF_8))
    RestUtils.refresh(index)

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE -> "arr")

    val df = sqc.read.options(newCfg).format("org.opensearch.spark.sql").load(target)
    df.printSchema()
    
    assertEquals("string", df.schema("top-level").dataType.typeName)
    assertEquals("array", df.schema("arr").dataType.typeName)
    assertEquals("struct", df.schema("arr").dataType.asInstanceOf[ArrayType].elementType.typeName)

    df.take(1).foreach(println)
    assertEquals(1, df.count())
  }
  
  @Test
  def testEmptyDataFrame() {
    val index = wrapIndex("spark-test")
    val (target, _) = makeTargets(index, "empty-dataframe")
    val idx = sqc.emptyDataFrame.saveToOpenSearch(target)
  }

  @Test(expected = classOf[OpenSearchHadoopIllegalArgumentException])
  def testIndexCreationDisabled() {
    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_INDEX_AUTO_CREATE -> "no")
    val index = wrapIndex("spark-test-non-existing")
    val (target, _) = makeTargets(index, "empty-dataframe")
    val idx = sqc.emptyDataFrame.saveToOpenSearch(target, newCfg)
  }

  @Test
  def testMultiFieldsWithSameName {
    val index = wrapIndex("sparksql-test-array-mapping-nested")
    val (target, docEndpoint) = makeTargets(index, "data")
    RestUtils.touch(index)

    // add some data
    val jsonDoc = s"""{
    |  "bar" : {
    |    "bar" : {
    |      "bar" : [{
    |          "bar" : 1
    |        }, {
    |          "bar" : 2
    |        }
    |      ],
    |      "level" : 2,
    |      "level3" : true
    |    },
    |    "foo2" : 10,
    |    "level" : 1,
    |    "level2" : 2
    |  },
    |  "foo1" : "$text",
    |  "level" : 0,
    |  "level1" : "string"
    |}
    """.stripMargin
    RestUtils.postData(docEndpoint, jsonDoc.getBytes(StringUtils.UTF_8))
    RestUtils.refresh(index)

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE -> "bar.bar.bar", "opensearch.resource" -> target)
    val cfgSettings = new SparkSettingsManager().load(sc.getConf).copy().merge(newCfg.asJava)
    val schema = SchemaUtilsTestable.discoverMapping(cfgSettings)
    val mapping = SchemaUtilsTestable.rowInfo(cfgSettings)

    val df = sqc.read.options(newCfg).format("org.opensearch.spark.sql").load(target)
    df.printSchema()
    df.take(1).foreach(println)
    assertEquals(1, df.count())
  }

  @Test
  def testNestedFieldArray {
    val index = wrapIndex("sparksql-test-nested-same-name-fields")
    val (target, _) = makeTargets(index, "data")
    RestUtils.touch(index)

    // add some data
    val jsonDoc = """{"foo" : 5, "nested": { "bar" : [{"date":"2015-01-01", "age":20},{"date":"2015-01-01", "age":20}], "what": "now" } }"""
    sc.makeRDD(Seq(jsonDoc)).saveJsonToEs(target)
    RestUtils.refresh(index)

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE -> "nested.bar")

    val df = sqc.read.options(newCfg).format("org.opensearch.spark.sql").load(target)
    df.printSchema()
    df.take(1).foreach(println)
    assertEquals(1, df.count())
  }

  @Test
  def testArrayValue {
    val index = wrapIndex("sparksql-test-array-value")
    val (target, _) = makeTargets(index, "data")
    RestUtils.touch(index)

    // add some data
    val jsonDoc = """{"array" : [1, 2, 4, 5] }"""
    sc.makeRDD(Seq(jsonDoc)).saveJsonToEs(target)
    RestUtils.refresh(index)

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE -> "array")

    val df = sqc.read.options(newCfg).format("org.opensearch.spark.sql").load(target)
    
    assertEquals("array", df.schema("array").dataType.typeName)
    assertEquals("long", df.schema("array").dataType.asInstanceOf[ArrayType].elementType.typeName)
    assertEquals(1, df.count())
    
    val first = df.first()
    val array = first.getSeq[Long](0)
    assertEquals(1l, array(0))
    assertEquals(4l, array(2))
  }

  @Test
  def testSometimesArrayValue {
    val index = wrapIndex("sparksql-test-sometimes-array-value")
    val (target, docEndpoint) = makeTargets(index, "data")
    RestUtils.touch(index)

    // add some data
    val jsonDoc1 = """{"array" : [1, 2, 4, 5] }"""
    val jsonDoc2 = """{"array" : 6 }"""
    sc.makeRDD(Seq(jsonDoc1, jsonDoc2)).saveJsonToEs(target)
    RestUtils.refresh(index)

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE -> "array")

    val df = sqc.read.options(newCfg).format("org.opensearch.spark.sql").load(target)

    assertEquals("array", df.schema("array").dataType.typeName)
    assertEquals("long", df.schema("array").dataType.asInstanceOf[ArrayType].elementType.typeName)
    assertEquals(2, df.count())

    val arrays = df.collect().map(_.getSeq[Long](0)).sortBy(_.head)

    {
      val array = arrays(0)
      assertEquals(1l, array(0))
      assertEquals(4l, array(2))
    }

    {
      val array = arrays(1)
      assertEquals(6l, array(0))
    }
  }

  @Test
  def testBasicRead() {
    val dataFrame = artistsAsDataFrame
    assertTrue(dataFrame.count > 300)
    dataFrame.registerTempTable("datfile")
    println(dataFrame.schema.treeString)
    //dataFrame.take(5).foreach(println)
    val results = sqc.sql("SELECT name FROM datfile WHERE id >=1 AND id <=10")
    //results.take(5).foreach(println)
  }

  @Test
  def testEmptyStrings(): Unit = {
    val data = Seq(("Java", "20000"), ("Python", ""), ("Scala", "3000"))
    val rdd: RDD[Row] = sc.parallelize(data).map(row => Row(row._1, row._2))
    val schema = StructType( Array(
      StructField("language", StringType,true),
      StructField("description", StringType,true)
    ))
    val inputDf = sqc.createDataFrame(rdd, schema)
    inputDf.write
      .format("org.opensearch.spark.sql")
      .save("empty_strings_test")
    val reader = sqc.read.format("org.opensearch.spark.sql")
    val outputDf = reader.load("empty_strings_test")
    assertEquals(data.size, outputDf.count)
    val nullDescriptionsDf = outputDf.filter("language = 'Python'")
    assertEquals(1, nullDescriptionsDf.count)
    assertEquals(null, nullDescriptionsDf.first().getAs("description"))

    val reader2 = sqc.read.format("org.opensearch.spark.sql").option("opensearch.field.read.empty.as.null", "no")
    val outputDf2 = reader2.load("empty_strings_test")
    assertEquals(data.size, outputDf2.count)
    val emptyDescriptionsDf = outputDf2.filter("language = 'Python'")
    assertEquals(1, emptyDescriptionsDf.count)
    assertEquals("", emptyDescriptionsDf.first().getAs("description"))
  }
  
  @Test
  def test0WriteFieldNameWithPercentage() {
    val index = wrapIndex("spark-test")
    val (target, _) = makeTargets(index, "scala-sql-field-with-percentage")

    val trip1 = Map("%s" -> "special")

    sc.makeRDD(Seq(trip1)).saveToOpenSearch(target)
  }
  
  @Test
  def test1ReadFieldNameWithPercentage() {
    val index = wrapIndex("spark-test")
    val (target, _) = makeTargets(index, "scala-sql-field-with-percentage")
    sqc.esDF(target).count()
  }

  @Test
  def testOpenSearchDataFrame1Write() {
    val dataFrame = artistsAsDataFrame

    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")
    dataFrame.saveToOpenSearch(target, cfg)
    assertTrue(RestUtils.exists(target))
    assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
  }

  @Test
  def testOpenSearchDataFrame1WriteCount() {
    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")

    val dataFrame = sqc.esDF(target, cfg)
    assertEquals(345, dataFrame.count())
  }

  @Test
  def testOpenSearchDataFrame1WriteWithMapping() {
    val dataFrame = artistsAsDataFrame

    val index = wrapIndex("sparksql-test-scala-basic-write-id-mapping")
    val (target, docEndpoint) = makeTargets(index, "data")

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_MAPPING_ID -> "id", OPENSEARCH_MAPPING_EXCLUDE -> "url")

    dataFrame.saveToOpenSearch(target, newCfg)
    assertTrue(RestUtils.exists(target))
    assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
    assertThat(RestUtils.exists(docEndpoint + "/1"), is(true))
    assertThat(RestUtils.get(target + "/_search?"), not(containsString("url")))
  }

  @Test
  def testOpenSearchDataFrame11CheckNoWriteNullValue() {
    val idx = wrapIndex("spark-test-null-data-test-0")
    val (target, docEndpoint) = makeTargets(idx, "data")

    val docs = Seq(
      """{"id":"1","name":{"first":"Robert","last":"Downey","suffix":"Jr"}}""",
      """{"id":"2","name":{"first":"Chris","last":"Evans"}}"""
    )

    val conf = Map(OPENSEARCH_MAPPING_ID -> "id")
    val rdd = sc.makeRDD(docs)
    val jsonDF = sqc.read.json(rdd).toDF.select("id", "name")
    jsonDF.saveToOpenSearch(target, conf)
    RestUtils.refresh(idx)
    val hit1 = RestUtils.get(s"$docEndpoint/1")
    val hit2 = RestUtils.get(s"$docEndpoint/2")

    assertThat(hit1, containsString("suffix"))
    assertThat(hit2, not(containsString("suffix")))
  }

  @Test
  def testOpenSearchDataFrame12CheckYesWriteNullValue() {
    val index = wrapIndex("spark-test-null-data-test-1")
    val (target, docEndpoint) = makeTargets(index, "data")

    val docs = Seq(
      """{"id":"1","name":{"first":"Robert","last":"Downey","suffix":"Jr"}}""",
      """{"id":"2","name":{"first":"Chris","last":"Evans"}}"""
    )

    val conf = Map(OPENSEARCH_MAPPING_ID -> "id", OPENSEARCH_SPARK_DATAFRAME_WRITE_NULL_VALUES -> "true")
    val rdd = sc.makeRDD(docs)
    val jsonDF = sqc.read.json(rdd).toDF.select("id", "name")
    jsonDF.saveToOpenSearch(target, conf)
    RestUtils.refresh(index)
    val hit1 = RestUtils.get(s"$docEndpoint/1")
    val hit2 = RestUtils.get(s"$docEndpoint/2")

    assertThat(hit1, containsString("suffix"))
    assertThat(hit2, containsString("suffix"))
  }

  @Test
  def testOpenSearchDataFrame11CheckNoWriteNullValueFromRows() {
    val index = wrapIndex("spark-test-null-data-test-2")
    val (target, docEndpoint) = makeTargets(index, "data")

    val data = Seq(
      Row("1", "Robert", "Downey", "Jr"),
      Row("2", "Chris", "Evans", null)
    )
    val schema = StructType(Array(
      StructField("id", StringType),
      StructField("first", StringType),
      StructField("last", StringType),
      StructField("suffix", StringType, nullable = true)
    ))

    val conf = Map("opensearch.mapping.id" -> "id")
    val rdd = sc.makeRDD(data)
    val df = sqc.createDataFrame(rdd, schema)
    df.saveToOpenSearch(target, conf)
    RestUtils.refresh(index)
    val hit1 = RestUtils.get(s"$docEndpoint/1")
    val hit2 = RestUtils.get(s"$docEndpoint/2")

    assertThat(hit1, containsString("suffix"))
    assertThat(hit2, not(containsString("suffix")))
  }

  @Test
  def testOpenSearchDataFrame12CheckYesWriteNullValueFromRows() {
    val index = wrapIndex("spark-test-null-data-test-3")
    val (target, docEndpoint) = makeTargets(index, "data")
    val data = Seq(
      Row("1", "Robert", "Downey", "Jr"),
      Row("2", "Chris", "Evans", null)
    )
    val schema = StructType(Array(
      StructField("id", StringType),
      StructField("first", StringType),
      StructField("last", StringType),
      StructField("suffix", StringType, nullable = true)
    ))

    val conf = Map("opensearch.mapping.id" -> "id", "opensearch.spark.dataframe.write.null" -> "true")
    val rdd = sc.makeRDD(data)
    val df = sqc.createDataFrame(rdd, schema)
    df.saveToOpenSearch(target, conf)
    RestUtils.refresh(index)
    val hit1 = RestUtils.get(s"$docEndpoint/1")
    val hit2 = RestUtils.get(s"$docEndpoint/2")

    assertThat(hit1, containsString("suffix"))
    assertThat(hit2, containsString("suffix"))
  }

  @Test
  def testOpenSearchDataFrame2Read() {
    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")

    val dataFrame = sqc.esDF(target, cfg)
    dataFrame.printSchema()
    val schema = dataFrame.schema.treeString
    assertTrue(schema.contains("id: long"))
    assertTrue(schema.contains("name: string"))
    assertTrue(schema.contains("pictures: string"))
    assertTrue(schema.contains("time: long"))
    assertTrue(schema.contains("url: string"))

    assertTrue(dataFrame.count > 300)

    //dataFrame.take(5).foreach(println)

    val tempTable = wrapIndex("basicRead")
    dataFrame.registerTempTable(tempTable)
    val nameRDD = sqc.sql(s"SELECT name FROM ${wrapTableName(tempTable)} WHERE id >= 1 AND id <=10")
    nameRDD.take(7).foreach(println)
    assertEquals(10, nameRDD.count)
  }

  @Test
  def testOpenSearchDataFrame2ReadWithIncludeFields() {
    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_READ_FIELD_INCLUDE -> "id, name, url")

    val dataFrame = sqc.esDF(target, newCfg)
    val schema = dataFrame.schema.treeString
    assertTrue(schema.contains("id: long"))
    assertTrue(schema.contains("name: string"))
    assertFalse(schema.contains("pictures: string"))
    assertFalse(schema.contains("time:"))
    assertTrue(schema.contains("url: string"))

    assertTrue(dataFrame.count > 300)

    //dataFrame.take(5).foreach(println)

    val tempTable = wrapIndex("basicRead")
    dataFrame.registerTempTable(tempTable)
    val nameRDD = sqc.sql(s"SELECT name FROM ${wrapTableName(tempTable)} WHERE id >= 1 AND id <=10")
    nameRDD.take(7).foreach(println)
    assertEquals(10, nameRDD.count)
  }

  @Test(expected = classOf[OpenSearchHadoopIllegalStateException])
  def testOpenSearchDataFrame2ReadWithUserSchemaSpecified() {
    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")

    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (OPENSEARCH_READ_FIELD_INCLUDE -> "id, name, url") += (OPENSEARCH_READ_SOURCE_FILTER -> "name")

    val dataFrame = sqc.esDF(target, newCfg)
    val schema = dataFrame.schema.treeString
    assertTrue(schema.contains("id: long"))
    assertTrue(schema.contains("name: string"))
    assertFalse(schema.contains("pictures: string"))
    assertFalse(schema.contains("time:"))
    assertTrue(schema.contains("url: string"))

    assertTrue(dataFrame.count > 300)

    //dataFrame.take(5).foreach(println)

    val tempTable = wrapIndex("basicRead")
    dataFrame.registerTempTable(tempTable)
    val nameRDD = sqc.sql(s"SELECT name FROM ${wrapTableName(tempTable)} WHERE id >= 1 AND id <=10")
    nameRDD.take(7)
  }

  @Test
  def testOpenSearchDataFrame2ReadWithAndWithoutQuery() {
    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")

    val dfNoQuery = sqc.esDF(target, cfg)
    val dfWQuery = sqc.esDF(target, "?q=name:me*", cfg)

    println(dfNoQuery.head())
    println(dfWQuery.head())

    //assertEquals(dfNoQuery.head().toString(), dfWQuery.head().toString())
  }

  @Test
  def testOpenSearchDataFrame2ReadWithAndWithoutQueryInJava() {
    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")

    val dfNoQuery = JavaOpenSearchSparkSQL.esDF(sqc, target, cfg.asJava)
    val query = s"""{ "query" : { "query_string" : { "query" : "name:me*" } } //, "fields" : ["name"]
                }"""
    val dfWQuery = JavaOpenSearchSparkSQL.esDF(sqc, target, query, cfg.asJava)

    println(dfNoQuery.head())
    println(dfWQuery.head())
    dfNoQuery.show(3)
    dfWQuery.show(3)

    //assertEquals(dfNoQuery.head().toString(), dfWQuery.head().toString())
  }

  @Test
  def testOpenSearchDataFrame3WriteWithRichMapping() {
    val input = datInput
    val data = sc.textFile(input)

    val schema = StructType(Seq(StructField("id", IntegerType, false),
      StructField("name", StringType, false),
      StructField("url", StringType, true),
      StructField("pictures", StringType, true),
      StructField("time", TimestampType, true),
      StructField("nested",
        StructType(Seq(StructField("id", IntegerType, false),
          StructField("name", StringType, false),
          StructField("url", StringType, true),
          StructField("pictures", StringType, true),
          StructField("time", TimestampType, true))), true)))

    val rowRDD = data.map(_.split("\t")).map(r => Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis()),
      Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis()))))
    val dataFrame = sqc.createDataFrame(rowRDD, schema)

    val index = wrapIndex("sparksql-test-scala-basic-write-rich-mapping-id-mapping")
    val (target, docEndpoint) = makeTargets(index, "data")
    dataFrame.saveToOpenSearch(target, Map(OPENSEARCH_MAPPING_ID -> "id"))
    assertTrue(RestUtils.exists(target))
    assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
    assertThat(RestUtils.exists(docEndpoint + "/1"), is(true))
  }

  @Test(expected = classOf[SparkException])
  def testOpenSearchDataFrame3WriteDecimalType() {
    val schema = StructType(Seq(StructField("decimal", DecimalType.USER_DEFAULT, false)))

    val rowRDD = sc.makeRDD(Seq(Row(Decimal(10))))
    val dataFrame = sqc.createDataFrame(rowRDD, schema)

    val index = wrapIndex("sparksql-test-decimal-exception")
    val (target, _) = makeTargets(index, "data")
    dataFrame.saveToOpenSearch(target)
  }

  @Test
  def testOpenSearchDataFrame4ReadRichMapping() {
    val index = wrapIndex("sparksql-test-scala-basic-write-rich-mapping-id-mapping")
    val (target, _) = makeTargets(index, "data")

    val dataFrame = sqc.esDF(target, cfg)

    assertTrue(dataFrame.count > 300)
    dataFrame.printSchema()
  }

  private def artistsAsDataFrame = {
    val input = datInput
    val data = sc.textFile(input)

    val schema = StructType(Seq(StructField("id", IntegerType, false),
      StructField("name", StringType, false),
      StructField("url", StringType, true),
      StructField("pictures", StringType, true),
      StructField("time", TimestampType, true)))

    val rowRDD = data.map(_.split("\t")).map(r => Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis())))
    val dataFrame = sqc.createDataFrame(rowRDD, schema)
    dataFrame
  }
  
  @Test
  def test5ScrollLimitWithEmptyPartition(): Unit = {
    val index = wrapIndex("scroll-limit")
    val (target, _) = makeTargets(index, "data")

    // Make index with two shards
    RestUtils.delete(index)
    RestUtils.put(index, """{"settings":{"number_of_shards":2,"number_of_replicas":0}}""".getBytes())
    RestUtils.refresh(index)

    // Write a single record to it (should have one empty shard)
    val data = artistsAsDataFrame
    val single = data.where(data("id").equalTo(1))
    assertEquals(1L, single.count())
    single.saveToOpenSearch(target)

    // Make sure that the scroll limit works with both a shard that has data and a shard that has nothing
    val count = sqc.read.format("opensearch").option("opensearch.scroll.limit", "10").load(target).count()
    assertEquals(1L, count)
  }

  @Test
  def testOpenSearchDataFrame50ReadAsDataSource() {
    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")
    var options = s"""resource '$target' """
    val table = wrapIndex("sqlbasicread1")

    val query = s"CREATE TEMPORARY TABLE ${wrapTableName(table)} "+
      " USING org.opensearch.spark.sql " +
      s" OPTIONS ($options)"

    println(query)

    val dataFrame = sqc.sql(query)

    val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)
    val dfLoad = sqc.read.format("opensearch").options(dsCfg.toMap).load()
    println("root data frame")
    dfLoad.printSchema()

    val results = dfLoad.filter(dfLoad("id") >= 1 && dfLoad("id") <= 10)
    println("results data frame")
    results.printSchema()

    val allRDD = sqc.sql(s"SELECT * FROM ${wrapTableName(table)} WHERE id >= 1 AND id <=10")
    println("select all rdd")
    allRDD.printSchema()

    val nameRDD = sqc.sql(s"SELECT name FROM ${wrapTableName(table)} WHERE id >= 1 AND id <=10")
    println("select name rdd")
    nameRDD.printSchema()

    assertEquals(10, nameRDD.count)
    nameRDD.take(7).foreach(println)
  }

  @Test
  def testOpenSearchDataFrameReadAsDataSourceWithMetadata() {
    assumeTrue(readMetadata)

    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")
    val table = wrapIndex("sqlbasicread2")

    val options = s"""path '$target' , readMetadata "true" """
    val dataFrame = sqc.sql(s"CREATE TEMPORARY TABLE ${wrapTableName(table)}" +
      " USING opensearch " +
      s" OPTIONS ($options)")

    val allRDD = sqc.sql(s"SELECT * FROM ${wrapTableName(table)} WHERE id >= 1 AND id <=10")
    allRDD.printSchema()
    allRDD.take(7).foreach(println)

    val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)
    val dfLoad = sqc.read.format("opensearch").options(dsCfg.toMap).load
    dfLoad.show()
  }

  @Test
  def testDataSource0Setup() {
    val index = wrapIndex("spark-test-scala-sql-varcols")
    val (target, _) = makeTargets(index, "data")
    val table = wrapIndex("sqlvarcol")

    val trip1 = Map("reason" -> "business", "airport" -> "SFO", "tag" -> "jan", "date" -> "2015-12-28T20:03:10Z")
    val trip2 = Map("participants" -> 5, "airport" -> "OTP", "tag" -> "feb", "date" -> "2013-12-28T20:03:10Z")
    val trip3 = Map("participants" -> 3, "airport" -> "MUC OTP SFO JFK", "tag" -> "long", "date" -> "2012-12-28T20:03:10Z")

    sc.makeRDD(Seq(trip1, trip2, trip3)).saveToOpenSearch(target)
  }

  private def opensearchDataSource(table: String) = {
    val index = wrapIndex("spark-test-scala-sql-varcols")
    val (target, docEndpoint) = makeTargets(index, "data")

    var options = s"""resource "$target" """


//    sqc.sql(s"CREATE TEMPORARY TABLE $table" +
//      " USING org.opensearch.spark.sql " +
//      s" OPTIONS ($options)")

    val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)
    sqc.read.format("org.opensearch.spark.sql").options(dsCfg.toMap).load
  }

  @Test
  def testDataSourcePushDown01EqualTo() {
    val df = opensearchDataSource("pd_equalto")
    val filter = df.filter(df("airport").equalTo("OTP"))

    filter.show

    if (strictPushDown) {
      assertEquals(0, filter.count())
      // however if we change the arguments to be lower cased, it will be Spark who's going to filter out the data
      return
    }
    else if (!keepHandledFilters) {
      // term query pick field with multi values
      assertEquals(2, filter.count())
      return
    }

    assertEquals(1, filter.count())
    assertEquals("feb", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown015NullSafeEqualTo() {
    val df = opensearchDataSource("pd_nullsafeequalto")
    val filter = df.filter(df("airport").eqNullSafe("OTP"))

    filter.show

    if (strictPushDown) {
      assertEquals(0, filter.count())
      // however if we change the arguments to be lower cased, it will be Spark who's going to filter out the data
      return
    }
    else if (!keepHandledFilters) {
      // term query pick field with multi values
      assertEquals(2, filter.count())
      return
    }

    assertEquals(1, filter.count())
    assertEquals("feb", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown02GT() {
    val df = opensearchDataSource("pd_gt")
    val filter = df.filter(df("participants").gt(3))
    assertEquals(1, filter.count())
    assertEquals("feb", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown03GTE() {
    val df = opensearchDataSource("pd_gte")
    val filter = df.filter(df("participants").geq(3))
    assertEquals(2, filter.count())
    assertEquals("long", filter.select("tag").sort("tag").take(2)(1)(0))
  }

  @Test
  def testDataSourcePushDown04LT() {
    val df = opensearchDataSource("pd_lt")
    df.printSchema()
    val filter = df.filter(df("participants").lt(5))
    assertEquals(1, filter.count())
    assertEquals("long", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown05LTE() {
    val df = opensearchDataSource("pd_lte")
    val filter = df.filter(df("participants").leq(5))
    assertEquals(2, filter.count())
    assertEquals("long", filter.select("tag").sort("tag").take(2)(1)(0))
  }

  @Test
  def testDataSourcePushDown06IsNull() {
    val df = opensearchDataSource("pd_is_null")
    val filter = df.filter(df("participants").isNull)
    assertEquals(1, filter.count())
    assertEquals("jan", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown07IsNotNull() {
    val df = opensearchDataSource("pd_is_not_null")
    val filter = df.filter(df("reason").isNotNull)
    assertEquals(1, filter.count())
    assertEquals("jan", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown08In() {
    val df = opensearchDataSource("pd_in")
    var filter = df.filter("airport IN ('OTP', 'SFO', 'MUC')")

    if (strictPushDown) {
      assertEquals(0, filter.count())
      // however if we change the arguments to be lower cased, it will be Spark who's going to filter out the data
      return
    }

    assertEquals(2, filter.count())
    assertEquals("jan", filter.select("tag").sort("tag").take(2)(1)(0))
  }

  @Test
  def testDataSourcePushDown08InWithNumbersAsStrings() {
    val df = opensearchDataSource("pd_in_numbers_strings")
    var filter = df.filter("date IN ('2015-12-28', '2012-12-28')")

    if (strictPushDown) {
      assertEquals(0, filter.count())
      // however if we change the arguments to be lower cased, it will be Spark who's going to filter out the data
      return
    }

    assertEquals(0, filter.count())
  }

  @Test
  def testDataSourcePushDown08InWithNumber() {
    val df = opensearchDataSource("pd_in_number")
    var filter = df.filter("participants IN (1, 2, 3)")

    assertEquals(1, filter.count())
    assertEquals("long", filter.select("tag").sort("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown08InWithNumberAndStrings() {
    val df = opensearchDataSource("pd_in_number")
    var filter = df.filter("participants IN (2, 'bar', 1, 'foo')")

    assertEquals(0, filter.count())
  }

  @Test
  def testDataSourcePushDown09StartsWith() {
    val df = opensearchDataSource("pd_starts_with")
    var filter = df.filter(df("airport").startsWith("O"))

    if (!keepHandledFilters && !strictPushDown) {
      // term query pick field with multi values
      assertEquals(2, filter.count())
      return
    }

    filter.show
    if (strictPushDown) {
      assertEquals(0, filter.count()) // Strict means specific terms matching, and the terms are lowercased
    } else {
      assertEquals(1, filter.count())
      assertEquals("feb", filter.select("tag").take(1)(0)(0))
    }
  }

  @Test
  def testDataSourcePushDown10EndsWith() {
    val df = opensearchDataSource("pd_ends_with")
    var filter = df.filter(df("airport").endsWith("O"))

    if (!keepHandledFilters && !strictPushDown) {
      // term query pick field with multi values
      assertEquals(2, filter.count())
      return
    }

    filter.show
    if (strictPushDown) {
      assertEquals(0, filter.count()) // Strict means specific terms matching, and the terms are lowercased
    } else {
      assertEquals(1, filter.count())
      assertEquals("jan", filter.select("tag").take(1)(0)(0))
    }
  }

  @Test
  def testDataSourcePushDown11Contains() {
    val df = opensearchDataSource("pd_contains")
    val filter = df.filter(df("reason").contains("us"))
    assertEquals(1, filter.count())
    assertEquals("jan", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown12And() {
    val df = opensearchDataSource("pd_and")
    var filter = df.filter(df("reason").isNotNull.and(df("tag").equalTo("jan")))

    assertEquals(1, filter.count())
    assertEquals("jan", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown13Not() {
    val df = opensearchDataSource("pd_not")
    val filter = df.filter(!df("reason").isNull)

    assertEquals(1, filter.count())
    assertEquals("jan", filter.select("tag").take(1)(0)(0))
  }

  @Test
  def testDataSourcePushDown14OR() {
    val df = opensearchDataSource("pd_or")
    var filter = df.filter(df("reason").contains("us").or(df("airport").equalTo("OTP")))

    if (strictPushDown) {
      // OTP fails due to strict matching/analyzed
      assertEquals(1, filter.count())
      return
    }

    if (!keepHandledFilters) {
      // term query pick field with multi values
      assertEquals(3, filter.count())
      return
    }

    assertEquals(2, filter.count())
    assertEquals("feb", filter.select("tag").sort("tag").take(1)(0)(0))
  }

  @Test
  def testOpenSearchSchemaFromDocsWithDifferentProperties() {
    val table = wrapIndex("sqlvarcol")
    opensearchDataSource(table)

    val index = wrapIndex("spark-test-scala-sql-varcols")
    val (target, _) = makeTargets(index, "data")

    var options = s"""resource '$target' """

    val s = sqc.sql(s"CREATE TEMPORARY TABLE ${wrapTableName(table)}" +
       " USING org.opensearch.spark.sql " +
       s" OPTIONS ($options)")

    val allResults = sqc.sql(s"SELECT * FROM ${wrapTableName(table)}")
    assertEquals(3, allResults.count())
    allResults.printSchema()

    val filter = sqc.sql(s"SELECT * FROM ${wrapTableName(table)} WHERE airport = 'OTP'")
    assertEquals(1, filter.count())

    val nullColumns = sqc.sql(s"SELECT reason, airport FROM ${wrapTableName(table)} ORDER BY airport")
    val rows = nullColumns.take(3)
    assertEquals("[null,MUC OTP SFO JFK]", rows(0).toString())
    assertEquals("[null,OTP]", rows(1).toString())
    assertEquals("[business,SFO]", rows(2).toString())
  }

  @Test
  def testJsonLoadAndSavedToEs() {
    val input = sqc.read.json(this.getClass.getResource("/simple.json").toURI().toString())
    println(input.schema.simpleString)

    val index = wrapIndex("spark-test-json-file")
    val (target, docEndpoint) = makeTargets(index, "data")
    input.saveToOpenSearch(target, cfg)

    val basic = sqc.read.json(this.getClass.getResource("/basic.json").toURI().toString())
    println(basic.schema.simpleString)
    basic.saveToOpenSearch(target, cfg)
  }

  @Test
  def testJsonLoadAndSavedToEsSchema() {
    assumeFalse(readMetadata)
    val input = sqc.read.json(this.getClass.getResource("/multi-level-doc.json").toURI().toString())
    println("JSON schema")
    println(input.schema.treeString)
    println(input.schema)
    val sample = input.take(1)(0).toString()

    val index = wrapIndex("spark-test-json-file-schema")
    val (target, docEndpoint) = makeTargets(index, "data")
    input.saveToOpenSearch(target, cfg)

    val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)
    val dfLoad = sqc.read.format("org.opensearch.spark.sql").options(dsCfg.toMap).load
    println("JSON schema")
    println(input.schema.treeString)
    println("Reading information from Elastic")
    println(dfLoad.schema.treeString)
    val item = dfLoad.take(1)(0)
    println(item.schema)
    println(item.toSeq)
    val nested = item.getStruct(1)
    println(nested.get(0))
    println(nested.get(0).getClass())

    assertEquals(input.schema.treeString, dfLoad.schema.treeString.replaceAll("float", "double"))
    assertEquals(sample, item.toString())
  }

  @Test
  def testTableJoining() {
    val index1Name = wrapIndex("sparksql-test-scala-basic-write")
    val (target1, _) = makeTargets(index1Name, "data")
    val index2Name = wrapIndex("sparksql-test-scala-basic-write-id-mapping")
    val (target2, _) = makeTargets(index2Name, "data")

    val table1 = sqc.read.format("org.opensearch.spark.sql").load(target1)
    val table2 = sqc.read.format("org.opensearch.spark.sql").load(target2)

    table1.persist(DISK_ONLY)
    table2.persist(DISK_ONLY_2)

    val table1Name = wrapIndex("table1")
    val table2Name = wrapIndex("table2")

    table1.registerTempTable(table1Name)
    table1.registerTempTable(table2Name)

    val join = sqc.sql(s"SELECT t1.name, t2.pictures FROM ${wrapTableName(table1Name)} t1, ${wrapTableName(table2Name)} t2 WHERE t1.id = t2.id")

    println(join.schema.treeString)
    println(join.take(1)(0).schema)
    println(join.take(1)(0)(0))
  }

  @Test
  def testOpenSearchDataFrame51WriteToExistingDataSource() {
    // to keep the select static
    assumeFalse(readMetadata)

    val index = wrapIndex("sparksql-test-scala-basic-write")
    val (target, _) = makeTargets(index, "data")
    val table = wrapIndex("table_insert")

    var options = s"resource '$target '"

    val dataFrame = sqc.sql(s"CREATE TEMPORARY TABLE ${wrapTableName(table)} " +
      s"USING org.opensearch.spark.sql " +
      s"OPTIONS ($options)");

    val insertRDD = sqc.sql(s"INSERT INTO TABLE ${wrapTableName(table)} SELECT 123456789, 'test-sql', 'http://test-sql.com', '', 12345")
    val df = sqc.table(wrapTableName(table))
    println(df.count)
    assertTrue(df.count > 100)
  }

  @Test
  def testOpenSearchDataFrame52OverwriteExistingDataSource() {
    // to keep the select static
    assumeFalse(readMetadata)

    val srcFrame = artistsAsDataFrame

    val index = wrapIndex("sparksql-test-scala-sql-overwrite")
    val (target, docEndpoint) = makeTargets(index, "data")
    srcFrame.saveToOpenSearch(target, cfg)

    val table = wrapIndex("table_overwrite")

    var options = s"resource '$target'"

    val dataFrame = sqc.sql(s"CREATE TEMPORARY TABLE ${wrapTableName(table)} " +
      s"USING org.opensearch.spark.sql " +
      s"OPTIONS ($options)");

    var df = sqc.table(wrapTableName(table))
    assertTrue(df.count > 1)
    val insertRDD = sqc.sql(s"INSERT OVERWRITE TABLE ${wrapTableName(table)} SELECT 123456789, 'test-sql', 'http://test-sql.com', '', 12345")
    df = sqc.table(wrapTableName(table))
    assertEquals(1, df.count)
  }

  @Test
  def testOpenSearchDataFrame52OverwriteExistingDataSourceWithJoinField() {

    // using long-form joiner values
    val schema = StructType(Seq(
      StructField("id", StringType, nullable = false),
      StructField("company", StringType, nullable = true),
      StructField("name", StringType, nullable = true),
      StructField("joiner", StructType(Seq(
        StructField("name", StringType, nullable = false),
        StructField("parent", StringType, nullable = true)
      )))
    ))

    val parents = Seq(
      Row("1", "Elastic", null, Row("company", null)),
      Row("2", "Fringe Cafe", null, Row("company", null)),
      Row("3", "WATIcorp", null, Row("company", null))
    )

    val firstChildren = Seq(
      Row("10", null, "kimchy", Row("employee", "1")),
      Row("20", null, "April Ryan", Row("employee", "2")),
      Row("21", null, "Charlie", Row("employee", "2")),
      Row("30", null, "Alvin Peats", Row("employee", "3"))
    )

    val index = wrapIndex("sparksql-test-scala-overwrite-join")
    val typename = "join"
    val (target, docEndpoint) = makeTargets(index, typename)
    RestUtils.delete(index)
    RestUtils.touch(index)
    if (TestUtils.isTypelessVersion(version)) {
      RestUtils.putMapping(index, typename, "data/join/mapping/typeless.json")
    } else {
      RestUtils.putMapping(index, typename, "data/join/mapping/typed.json")
    }

    sqc.createDataFrame(sc.makeRDD(parents ++ firstChildren), schema)
      .write
      .format("opensearch")
      .options(Map(OPENSEARCH_MAPPING_ID -> "id", OPENSEARCH_MAPPING_JOIN -> "joiner"))
      .save(target)

    assertThat(RestUtils.get(docEndpoint + "/10?routing=1"), containsString("kimchy"))
    assertThat(RestUtils.get(docEndpoint + "/10?routing=1"), containsString(""""_routing":"1""""))

    // Overwrite the data using a new dataset:
    val newChildren = Seq(
      Row("110", null, "costinl", Row("employee", "1")),
      Row("111", null, "jbaiera", Row("employee", "1")),
      Row("121", null, "Charlie", Row("employee", "2")),
      Row("130", null, "Damien", Row("employee", "3"))
    )

    sqc.createDataFrame(sc.makeRDD(parents ++ newChildren), schema)
      .write
      .format("opensearch")
      .options(cfg ++ Map(OPENSEARCH_MAPPING_ID -> "id", OPENSEARCH_MAPPING_JOIN -> "joiner"))
      .mode(SaveMode.Overwrite)
      .save(target)

    assertFalse(RestUtils.exists(docEndpoint + "/10?routing=1"))
    assertThat(RestUtils.get(docEndpoint + "/110?routing=1"), containsString("costinl"))
    assertThat(RestUtils.get(docEndpoint + "/110?routing=1"), containsString(""""_routing":"1""""))
  }

  @Test
  def testOpenSearchDataFrame53OverwriteExistingDataSourceFromAnotherDataSource() {
    // to keep the select static
    assumeFalse(readMetadata)

    val sourceIndex = wrapIndex("sparksql-test-scala-basic-write")
    val (source, _) = makeTargets(sourceIndex, "data")
    val targetIndex = wrapIndex("sparksql-test-scala-sql-overwrite-from-df")
    val (target, _) = makeTargets(targetIndex, "data")

    val dstFrame = artistsAsDataFrame
    dstFrame.saveToOpenSearch(target, cfg)

    val srcTable = wrapIndex("table_overwrite_src")
    val dstTable = wrapIndex("table_overwrite_dst")

    var dstOptions = s"resource '$source'"

    var srcOptions = s"resource '$target'"

    val srcFrame = sqc.sql(s"CREATE TEMPORARY TABLE ${wrapTableName(srcTable)} " +
      s"USING org.opensearch.spark.sql " +
      s"OPTIONS ($srcOptions)");

    val dataFrame = sqc.sql(s"CREATE TEMPORARY TABLE ${wrapTableName(dstTable)} " +
      s"USING org.opensearch.spark.sql " +
      s"OPTIONS ($dstOptions)");

    val insertRDD = sqc.sql(s"INSERT OVERWRITE TABLE ${wrapTableName(dstTable)} SELECT * FROM ${wrapTableName(srcTable)}")
    val df = sqc.table(wrapTableName(dstTable))
    println(df.count)
    assertTrue(df.count > 100)
  }

  @Test
  def testOpenSearchDataFrame60DataSourceSaveModeError() {
    val srcFrame = sqc.read.json(this.getClass.getResource("/small-sample.json").toURI().toString())
    val index = wrapIndex("sparksql-test-savemode_error")
    val (target, _) = makeTargets(index, "data")
    val table = wrapIndex("save_mode_error")

    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.ErrorIfExists).save(target)
    try {
      srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.ErrorIfExists).save(target)
      fail()
    } catch {
      case _: Throwable => // swallow
    }
  }

  @Test
  def testOpenSearchDataFrame60DataSourceSaveModeAppend() {
    val srcFrame = sqc.read.json(this.getClass.getResource("/small-sample.json").toURI().toString())
    srcFrame.printSchema()
    val index = wrapIndex("sparksql-test-savemode_append")
    val (target, _) = makeTargets(index, "data")
    val table = wrapIndex("save_mode_append")

    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Append).save(target)
    val df = OpenSearchSparkSQL.esDF(sqc, target)

    assertEquals(3, df.count())
    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Append).save(target)
    assertEquals(6, df.count())
  }

  @Test
  def testOpenSearchDataFrame60DataSourceSaveModeOverwrite() {
    val srcFrame = sqc.read.json(this.getClass.getResource("/small-sample.json").toURI().toString())
    val index = wrapIndex("sparksql-test-savemode_overwrite")
    val (target, _) = makeTargets(index, "data")

    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Overwrite).save(target)
    val df = OpenSearchSparkSQL.esDF(sqc, target)

    assertEquals(3, df.count())
    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Overwrite).save(target)
    assertEquals(3, df.count())
  }

  @Test
  def testOpenSearchDataFrame60DataSourceSaveModeOverwriteWithID() {
    val srcFrame = sqc.read.json(this.getClass.getResource("/small-sample.json").toURI().toString())
    val index = wrapIndex("sparksql-test-savemode_overwrite_id")
    val (target, docEndpoint) = makeTargets(index, "data")

    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Overwrite).option("opensearch.mapping.id", "number").save(target)
    val df = OpenSearchSparkSQL.esDF(sqc, target)

    assertEquals(3, df.count())
    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Overwrite).option("opensearch.mapping.id", "number").save(target)
    assertEquals(3, df.count())
  }

  @Test
  def testOpenSearchDataFrame60DataSourceSaveModeIgnore() {
    val srcFrame = sqc.read.json(this.getClass.getResource("/small-sample.json").toURI().toString())
    val index = wrapIndex("sparksql-test-savemode_ignore")
    val (target, _) = makeTargets(index, "data")
    val table = wrapIndex("save_mode_ignore")

    srcFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Ignore).save(target)
    val df = OpenSearchSparkSQL.esDF(sqc, target)

    assertEquals(3, df.count())
    // should not go through
    artistsAsDataFrame.write.format("org.opensearch.spark.sql").mode(SaveMode.Ignore).save(target)
    // if it does, this will likely throw an error
    assertEquals(3, df.count())
  }
  
  @Test
  def testArrayWithNestedObject() {
    val json = """{"0ey" : "val", "another-array": [{ "item" : 1, "key": { "key_a":"val_a", "key_b":"val_b" } }, { "item" : 2, "key": { "key_a":"val_c","key_b":"val_d" } } ]}"""
    val index = wrapIndex("sparksql-test-array-with-nested-object")
    val (target, _) = makeTargets(index, "data")
    sc.makeRDD(Seq(json)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").option(OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE, "another-array").load(target)

    df.printSchema()
    assertEquals("array", df.schema("another-array").dataType.typeName)
    val array = df.schema("another-array").dataType
    val key = array.asInstanceOf[ArrayType].elementType.asInstanceOf[StructType]("key")
    assertEquals("struct", key.dataType.typeName)
    
    val head = df.head
    println(head)
    val ky = head.getString(0)
    assertEquals("val", ky)
    // array
    val arr = head.getSeq[Row](1)
 
    val one = arr(0)
    assertEquals(1, one.getLong(0))
    val nestedOne = one.getStruct(1)
    assertEquals("val_a", nestedOne.getString(0))
    assertEquals("val_b", nestedOne.getString(1))
    
    val two = arr(1)
    assertEquals(2, two.getLong(0))
    val nestedTwo = two.getStruct(1)
    assertEquals("val_c", nestedTwo.getString(0))
    assertEquals("val_d", nestedTwo.getString(1))
  }


  @Test
  def testNestedEmptyArray() {
    val json = """{"foo" : 5, "nested": { "bar" : [], "what": "now" } }"""
    val index = wrapIndex("sparksql-test-empty-nested-array")
    val (target, _) = makeTargets(index, "data")
    sc.makeRDD(Seq(json)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(target)
    
    assertEquals("long", df.schema("foo").dataType.typeName)
    assertEquals("struct", df.schema("nested").dataType.typeName)
    val struct = df.schema("nested").dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("what"))
    assertEquals("string", struct("what").dataType.typeName)
        
    val head = df.head
    assertEquals(5l, head(0))
    assertEquals("now", head.getStruct(1)(0))
  }

  @Test
  def testDoubleNestedArray() {
    val json = """{"foo" : [5,6], "nested": { "bar" : [{"date":"2015-01-01", "scores":[1,2]},{"date":"2015-01-01", "scores":[3,4]}], "what": "now" } }"""
    val index = wrapIndex("sparksql-test-double-nested-array")
    val (target, docEndpoint) = makeTargets(index, "data")
    sc.makeRDD(Seq(json)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").option(OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE, "nested.bar,foo,nested.bar.scores").load(target)

    assertEquals("array", df.schema("foo").dataType.typeName)
    val bar = df.schema("nested").dataType.asInstanceOf[StructType]("bar")
    assertEquals("array", bar.dataType.typeName)
    val scores = bar.dataType.asInstanceOf[ArrayType].elementType.asInstanceOf[StructType]("scores")
    assertEquals("array", scores.dataType.typeName)
    
    val head = df.head
    val foo = head.getSeq[Long](0)
    assertEquals(5, foo(0))
    assertEquals(6, foo(1))
    // nested
    val nested = head.getStruct(1)
    assertEquals("now", nested.getString(1))
    val nestedDate = nested.getSeq[Row](0)
    val nestedScores = nestedDate(0).getSeq[Long](1)
    assertEquals(2l, nestedScores(1))
  }

  //@Test
  def testArrayExcludes() {
    val json = """{"foo" : 6, "nested": { "bar" : [{"date":"2015-01-01", "scores":[1,2]},{"date":"2015-01-01", "scores":[3,4]}], "what": "now" } }"""
    val index = wrapIndex("sparksql-test-nested-array-exclude")
    val (target, docEndpoint) = makeTargets(index, "data")
    sc.makeRDD(Seq(json)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").option(OPENSEARCH_READ_FIELD_EXCLUDE, "nested.bar").load(target)

    assertEquals("long", df.schema("foo").dataType.typeName)
    assertEquals("struct", df.schema("nested").dataType.typeName)
    val struct = df.schema("nested").dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("what"))
    assertEquals("string", struct("what").dataType.typeName)

    df.printSchema()
    val first = df.first
    println(first)
    println(first.getStruct(1))
    assertEquals(6, first.getLong(0))
    assertEquals("now", first.getStruct(1).getString(0))
  }

  @Test
  def testMultiDepthArray() {
    val json = """{"rect":{"type":"multipoint","coordinates":[[ [50,32],[69,32],[69,50],[50,50],[50,32] ]]}}"""
    val index = wrapIndex("sparksql-test-geo")
    val (target, _) = makeTargets(index, "data")
    sc.makeRDD(Seq(json)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").option(OPENSEARCH_READ_FIELD_AS_ARRAY_INCLUDE, "rect.coordinates:2").load(target)
    
    val coords = df.schema("rect").dataType.asInstanceOf[StructType]("coordinates")
    assertEquals("array", coords.dataType.typeName)
    val nested = coords.dataType.asInstanceOf[ArrayType].elementType
    assertEquals("array", nested.typeName)
    assertEquals("long", nested.asInstanceOf[ArrayType].elementType.typeName)

    val first = df.first
    val vals = first.getStruct(0).getSeq[Seq[Seq[Long]]](0)(0)(0)
    assertEquals(50, vals(0))
    assertEquals(32, vals(1))
  }

  @Test
  def testJoinField(): Unit = {
    // test mix of short-form and long-form joiner values
    val company1 = Map("id" -> "1", "company" -> "Elastic", "joiner" -> "company")
    val company2 = Map("id" -> "2", "company" -> "Fringe Cafe", "joiner" -> Map("name" -> "company"))
    val company3 = Map("id" -> "3", "company" -> "WATIcorp", "joiner" -> Map("name" -> "company"))

    val employee1 = Map("id" -> "10", "name" -> "kimchy", "joiner" -> Map("name" -> "employee", "parent" -> "1"))
    val employee2 = Map("id" -> "20", "name" -> "April Ryan", "joiner" -> Map("name" -> "employee", "parent" -> "2"))
    val employee3 = Map("id" -> "21", "name" -> "Charlie", "joiner" -> Map("name" -> "employee", "parent" -> "2"))
    val employee4 = Map("id" -> "30", "name" -> "Alvin Peats", "joiner" -> Map("name" -> "employee", "parent" -> "3"))

    val parents = Seq(company1, company2, company3)
    val children = Seq(employee1, employee2, employee3, employee4)
    val docs = parents ++ children

    {
      val index = wrapIndex("sparksql-test-scala-write-join-separate")
      val typename = "join"
      val (target, docEndpoint) = makeTargets(index, typename)
      if (TestUtils.isTypelessVersion(version)) {
        RestUtils.putMapping(index, typename, "data/join/mapping/typeless.json")
      } else {
        RestUtils.putMapping(index, typename, "data/join/mapping/typed.json")
      }

      sc.makeRDD(parents).saveToOpenSearch(target, Map(OPENSEARCH_MAPPING_ID -> "id", OPENSEARCH_MAPPING_JOIN -> "joiner"))
      sc.makeRDD(children).saveToOpenSearch(target, Map(OPENSEARCH_MAPPING_ID -> "id", OPENSEARCH_MAPPING_JOIN -> "joiner"))

      assertThat(RestUtils.get(docEndpoint + "/10?routing=1"), containsString("kimchy"))
      assertThat(RestUtils.get(docEndpoint + "/10?routing=1"), containsString(""""_routing":"1""""))

      val df = sqc.read.format("opensearch").load(target)
      val data = df.where(df("id").equalTo("1").or(df("id").equalTo("10"))).sort(df("id")).collect()

      {
        val record1 = data(0)
        assertNotNull(record1.getStruct(record1.fieldIndex("joiner")))
        val joiner = record1.getStruct(record1.fieldIndex("joiner"))
        assertNotNull(joiner.getString(joiner.fieldIndex("name")))
      }

      {
        val record10 = data(1)
        assertNotNull(record10.getStruct(record10.fieldIndex("joiner")))
        val joiner = record10.getStruct(record10.fieldIndex("joiner"))
        assertNotNull(joiner.getString(joiner.fieldIndex("name")))
        assertNotNull(joiner.getString(joiner.fieldIndex("parent")))
      }

    }
    {
      val index = wrapIndex("sparksql-test-scala-write-join-combined")
      val typename = "join"
      val (target, docEndpoint) = makeTargets(index, typename)
      if (TestUtils.isTypelessVersion(version)) {
        RestUtils.putMapping(index, typename, "data/join/mapping/typeless.json")
      } else {
        RestUtils.putMapping(index, typename, "data/join/mapping/typed.json")
      }

      sc.makeRDD(docs).saveToOpenSearch(target, Map(OPENSEARCH_MAPPING_ID -> "id", OPENSEARCH_MAPPING_JOIN -> "joiner"))

      assertThat(RestUtils.get(docEndpoint + "/10?routing=1"), containsString("kimchy"))
      assertThat(RestUtils.get(docEndpoint + "/10?routing=1"), containsString(""""_routing":"1""""))

      val df = sqc.read.format("opensearch").load(target)
      val data = df.where(df("id").equalTo("1").or(df("id").equalTo("10"))).sort(df("id")).collect()

      {
        val record1 = data(0)
        assertNotNull(record1.getStruct(record1.fieldIndex("joiner")))
        val joiner = record1.getStruct(record1.fieldIndex("joiner"))
        assertNotNull(joiner.getString(joiner.fieldIndex("name")))
      }

      {
        val record10 = data(1)
        assertNotNull(record10.getStruct(record10.fieldIndex("joiner")))
        val joiner = record10.getStruct(record10.fieldIndex("joiner"))
        assertNotNull(joiner.getString(joiner.fieldIndex("name")))
        assertNotNull(joiner.getString(joiner.fieldIndex("parent")))
      }
    }
  }

  @Test
  def testGeoPointAsLatLonString() {
    val mapping = wrapMapping("data", s"""{
    |    "properties": {
    |      "name": {
    |        "type": "$keyword"
    |      },
    |      "location": {
    |        "type": "geo_point"
    |      }
    |    }
    |  }
    """.stripMargin)
// Applies in ES 2.x           
//    |          "fielddata" : {
//    |            "format" : "compressed",
//    |
//    |          }

    
    val index = wrapIndex("sparksql-test-geopoint-latlonstring-geopoint")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val latLonString = """{ "name" : "Chipotle Mexican Grill", "location": "40.715, -74.011" }""".stripMargin
    sc.makeRDD(Seq(latLonString)).saveJsonToEs(target)
    
    RestUtils.refresh(index)
    
    val df = sqc.read.format("opensearch").load(index)
 
    val dataType = df.schema("location").dataType
    assertEquals("string", dataType.typeName)

    val head = df.head()
    assertThat(head.getString(0), containsString("715, "))
    assertThat(head.getString(1), containsString("Chipotle"))
  }
  
  @Test
  def testGeoPointAsGeoHashString() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_point"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geopoint-geohash-geopoint")
    val typed = "data"
    val (target, docEndpoint) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val geohash = """{ "name": "Red Pepper Restaurant", "location": "9qh0kemfy5k3" }""".stripMargin
    sc.makeRDD(Seq(geohash)).saveJsonToEs(target)
    
    RestUtils.refresh(index)
    
    val df = sqc.read.format("opensearch").load(index)
 
    val dataType = df.schema("location").dataType
    assertEquals("string", dataType.typeName)

    val head = df.head()
    assertThat(head.getString(0), containsString("9qh0"))
    assertThat(head.getString(1), containsString("Pepper"))
  }
    
  @Test
  def testGeoPointAsArrayOfDoubles() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_point"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geopoint-array-geopoint")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val arrayOfDoubles = """{ "name": "Mini Munchies Pizza", "location": [ -73.983, 40.719 ]}""".stripMargin
    sc.makeRDD(Seq(arrayOfDoubles)).saveJsonToEs(target)
    
    RestUtils.refresh(index)
    
    val df = sqc.read.format("opensearch").load(index)

    val dataType = df.schema("location").dataType
    assertEquals("array", dataType.typeName)
    val array = dataType.asInstanceOf[ArrayType]
    assertEquals(DoubleType, array.elementType)
   
    val head = df.head()
    println(head(0))
    assertThat(head.getString(1), containsString("Mini"))
  }

  @Test
  def testGeoPointAsObject() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_point"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geopoint-object-geopoint")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val lonLatObject = """{ "name" : "Pala Pizza","location": {"lat":40.722, "lon":-73.989} }""".stripMargin
    sc.makeRDD(Seq(lonLatObject)).saveJsonToEs(target)
    
    RestUtils.refresh(index)
    
    val df = sqc.read.format("opensearch").load(index)
    
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)
    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("lon"))
    assertTrue(struct.fieldNames.contains("lat"))
    assertEquals("double", struct("lon").dataType.simpleString)
    assertEquals("double", struct("lat").dataType.simpleString)
    
    val head = df.head()
    println(head)
    val obj = head.getStruct(0)
    assertThat(obj.getDouble(0), is(40.722d))
    assertThat(obj.getDouble(1), is(-73.989d))
    
    assertThat(head.getString(1), containsString("Pizza"))
  }

  @Test
  def testGeoShapePoint() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_shape"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geoshape-point-geoshape")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val point = """{"name":"point","location":{ "type" : "point", "coordinates": [100.0, 0.0] }}""".stripMargin

    sc.makeRDD(Seq(point)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)
 
    println(df.schema.treeString)
 
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)

    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("type"))
    var coords = struct("coordinates").dataType
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    assertEquals("double", coords.typeName)

    val head = df.head()
    val obj = head.getStruct(0)
    assertThat(obj.getString(0), is("point"))
    val array = obj.getSeq[Double](1)
    assertThat(array(0), is(100.0d))
    assertThat(array(1), is(0.0d))
  }

  @Test
  def testGeoShapeLine() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_shape"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geoshape-linestring-geoshape")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val line = """{"name":"line","location":{ "type": "linestring", "coordinates": [[-77.03, 38.89], [-77.00, 38.88]]} }""".stripMargin
      
    sc.makeRDD(Seq(line)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)
 
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)

    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("type"))
    var coords = struct("coordinates").dataType
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    assertEquals("array", coords.typeName)
    assertEquals("double", coords.asInstanceOf[ArrayType].elementType.typeName)

    val head = df.head()
    val obj = head.getStruct(0)
    assertThat(obj.getString(0), is("linestring"))
    val array = obj.getSeq[Seq[Double]](1)
    assertThat(array(0)(0), is(-77.03d))
    assertThat(array(0)(1), is(38.89d))
  }

  @Test
  def testGeoShapePolygon() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_shape"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geoshape-poly-geoshape")
    val typed = "data"
    val (target, docEndpoint) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val polygon = """{"name":"polygon","location":{ "type" : "Polygon", "coordinates": [[ [100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 1.0], [100.0, 0.0] ]], "crs":null, "foo":"bar" }}""".stripMargin
      
    sc.makeRDD(Seq(polygon)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)
 
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)

    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("type"))
    var coords = struct("coordinates").dataType
    // level 1
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    // level 2
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    // level 3
    assertEquals("double", coords.asInstanceOf[ArrayType].elementType.typeName)

    val head = df.head()
    val obj = head.getStruct(0)
    assertThat(obj.getString(0), is("Polygon"))
    val array = obj.getSeq[Seq[Seq[Double]]](1)
    assertThat(array(0)(0)(0), is(100.0d))
    assertThat(array(0)(0)(1), is(0.0d))
  }

  @Test
  def testGeoShapePointMultiPoint() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_shape"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geoshape-multipoint-geoshape")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val multipoint = """{"name":"multipoint","location":{ "type" : "multipoint", "coordinates": [ [100.0, 0.0], [101.0, 0.0] ] }}""".stripMargin
      
    sc.makeRDD(Seq(multipoint)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)
 
    println(df.schema.treeString)
 
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)

    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("type"))
    var coords = struct("coordinates").dataType
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    assertEquals("array", coords.typeName)
    assertEquals("double", coords.asInstanceOf[ArrayType].elementType.typeName)
    
    val head = df.head()
    val obj = head.getStruct(0)
    assertThat(obj.getString(0), is("multipoint"))
    val array = obj.getSeq[Seq[Double]](1)
    assertThat(array(0)(0), is(100.0d))
    assertThat(array(0)(1), is(0.0d))
  }

  @Test
  def testGeoShapeMultiLine() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_shape"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geoshape-multiline-geoshape")
    val typed = "data"
    val (target, docEndpoint) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val multiline = """{"name":"multi-line","location":{ "type": "multilinestring", "coordinates":[ [[-77.0, 38.8], [-78.0, 38.8]], [[100.0, 0.0], [101.0, 1.0]] ]} }""".stripMargin
      
    sc.makeRDD(Seq(multiline)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)
 
    println(df.schema.treeString)
 
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)

    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("type"))
    var coords = struct("coordinates").dataType
    // level 1
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    // level 2
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    // level 3
    assertEquals("double", coords.asInstanceOf[ArrayType].elementType.typeName)

    val head = df.head()
    val obj = head.getStruct(0)
    assertThat(obj.getString(0), is("multilinestring"))
    val array = obj.getSeq[Seq[Seq[Double]]](1)
    assertThat(array(0)(0)(0), is(-77.0d))
    assertThat(array(0)(0)(1), is(38.8d))
  }
  
  @Test
  def testGeoShapeMultiPolygon() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_shape"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geoshape-multi-poly-geoshape")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val multipoly = """{"name":"multi-poly","location":{ "type" : "multipolygon", "coordinates": [ [[[100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 0.0] ]], [[[103.0, 0.0], [104.0, 0.0], [104.0, 1.0], [103.0, 0.0] ]] ]}}""".stripMargin
      
    sc.makeRDD(Seq(multipoly)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)
 
    println(df.schema.treeString)
 
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)

    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("type"))
    var coords = struct("coordinates").dataType
    // level 1
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    // level 2
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    // level 3
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    // level 4
    assertEquals("double", coords.asInstanceOf[ArrayType].elementType.typeName)

    val head = df.head()
    val obj = head.getStruct(0)
    assertThat(obj.getString(0), is("multipolygon"))
    val array = obj.getSeq[Seq[Seq[Seq[Double]]]](1)
    assertThat(array(0)(0)(0)(0), is(100.0d))
    assertThat(array(0)(0)(0)(1), is(0.0d))
  }
          
  @Test
  def testGeoShapeEnvelope() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": {
    |          "type": "$keyword"
    |        },
    |        "location": {
    |          "type": "geo_shape"
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-geoshape-envelope-geoshape")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val envelope = """{"name":"envelope","location":{ "type" : "envelope", "coordinates": [[-45.0, 45.0], [45.0, -45.0] ] }}""".stripMargin
      
    sc.makeRDD(Seq(envelope)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)
 
    val dataType = df.schema("location").dataType
    assertEquals("struct", dataType.typeName)

    val struct = dataType.asInstanceOf[StructType]
    assertTrue(struct.fieldNames.contains("type"))
    var coords = struct("coordinates").dataType
    assertEquals("array", coords.typeName)
    coords = coords.asInstanceOf[ArrayType].elementType
    assertEquals("array", coords.typeName)
    assertEquals("double", coords.asInstanceOf[ArrayType].elementType.typeName)

    val head = df.head()
    val obj = head.getStruct(0)
    assertThat(obj.getString(0), is("envelope"))
    val array = obj.getSeq[Seq[Double]](1)
    assertThat(array(0)(0), is(-45.0d))
    assertThat(array(0)(1), is(45.0d))
  }

  @Test
  def testNested() {
    val mapping = wrapMapping("data", s"""{
    |      "properties": {
    |        "name": { "type": "$keyword" },
    |        "employees": {
    |          "type": "nested",
    |          "properties": {
    |            "name": {"type": "$keyword"},
    |            "salary": {"type": "long"}
    |          }
    |        }
    |      }
    |  }
    """.stripMargin)

    val index = wrapIndex("sparksql-test-nested-simple-nested")
    val typed = "data"
    val (target, _) = makeTargets(index, typed)
    RestUtils.touch(index)
    RestUtils.putMapping(index, typed, mapping.getBytes(StringUtils.UTF_8))

    val data = """{"name":"nested-simple","employees":[{"name":"anne","salary":6},{"name":"bob","salary":100}, {"name":"charlie","salary":15}] }""".stripMargin
      
    sc.makeRDD(Seq(data)).saveJsonToEs(target)
    val df = sqc.read.format("opensearch").load(index)

    println(df.schema.treeString)
    
    val dataType = df.schema("employees").dataType
    assertEquals("array", dataType.typeName)
    val array = dataType.asInstanceOf[ArrayType]
    assertEquals("struct", array.elementType.typeName)
    val struct = array.elementType.asInstanceOf[StructType]
    assertEquals("string", struct("name").dataType.typeName)
    assertEquals("long", struct("salary").dataType.typeName)

    val head = df.head()
    val nested = head.getSeq[Row](0);
    assertThat(nested.size, is(3))
    assertEquals(nested(0).getString(0), "anne")
    assertEquals(nested(0).getLong(1), 6)
  }

  
  @Test
  def testMultiIndexes() {
    // add some data
    val jsonDoc = """{"artist" : "buckethead", "album": "mirror realms" }"""
    val index1 = wrapIndex("sparksql-multi-index-1")
    val (target1, _) = makeTargets(index1, "data")
    val index2 = wrapIndex("sparksql-multi-index-2")
    val (target2, _) = makeTargets(index2, "data")
    sc.makeRDD(Seq(jsonDoc)).saveJsonToEs(target1)
    sc.makeRDD(Seq(jsonDoc)).saveJsonToEs(target2)
    RestUtils.refresh(wrapIndex("sparksql-multi-index-1"))
    RestUtils.refresh(wrapIndex("sparksql-multi-index-2"))
    val multiIndex = wrapIndex("sparksql-multi-index-1,") + index2
    val df = sqc.read.format("opensearch").load(multiIndex)
    df.show
    println(df.selectExpr("count(*)").show(5))
    assertEquals(2, df.count())
  }

  @Test
  def testArraysAndNulls() {
    val index = wrapIndex("sparksql-test-arrays-and-nulls")
    val typed = "data"
    val (target, docPath) = makeTargets(index, typed)
    RestUtils.touch(index)
    val document1 = """{ "id": 1, "status_code" : [123]}""".stripMargin
    val document2 = """{ "id" : 2, "status_code" : []}""".stripMargin
    val document3 = """{ "id" : 3, "status_code" : null}""".stripMargin
    sc.makeRDD(Seq(document1, document2, document3)).saveJsonToEs(target)
    RestUtils.refresh(index)
    val df = sqc.read.format("opensearch").option("opensearch.read.field.as.array.include","status_code").load(index)
      .select("id", "status_code")
    var result = df.where("id = 1").first().getList(1)
    assertEquals(123, result.get(0))
    result = df.where("id = 2").first().getList(1)
    assertTrue(result.isEmpty)
    assertTrue(df.where("id = 3").first().isNullAt(1))
  }

  @Test
  def testReadFieldInclude(): Unit = {
    val data = Seq(
      Row(Row(List(Row("hello","2"), Row("world","1"))))
    )
    val rdd: RDD[Row] = sc.parallelize(data)
    val schema = new StructType()
      .add("features", new StructType()
        .add("hashtags", new ArrayType(new StructType()
          .add("text", StringType)
          .add("count", StringType), true)))

    val inputDf = sqc.createDataFrame(rdd, schema)
    inputDf.write
      .format("org.opensearch.spark.sql")
      .save("read_field_include_test")
    val reader = sqc.read.format("org.opensearch.spark.sql").option("opensearch.read.field.as.array.include","features.hashtags")

    // No "opensearch.read.field.include", so everything is included:
    var df = reader.load("read_field_include_test")
    var result = df.select("features.hashtags").first().getAs[IndexedSeq[Row]](0)
    assertEquals(2, result(0).size)
    assertEquals("hello", result(0).getAs("text"))
    assertEquals("2", result(0).getAs("count"))

    // "opensearch.read.field.include" has trailing wildcard, so everything included:
    df = reader.option("opensearch.read.field.include","features.hashtags.*").load("read_field_include_test")
    result = df.select("features.hashtags").first().getAs[IndexedSeq[Row]](0)
    assertEquals(2, result(0).size)
    assertEquals("hello", result(0).getAs("text"))
    assertEquals("2", result(0).getAs("count"))

    // "opensearch.read.field.include" includes text but not count
    df = reader.option("opensearch.read.field.include","features.hashtags.text").load("read_field_include_test")
    result = df.select("features.hashtags").first().getAs[IndexedSeq[Row]](0)
    assertEquals(1, result(0).size)
    assertEquals("hello", result(0).getAs("text"))

    // "opensearch.read.field.include" does not include the leaves in the hierarchy so they won't be returned
    df = reader.option("opensearch.read.field.include","features.hashtags").load("read_field_include_test")
    result = df.select("features.hashtags").first().getAs[IndexedSeq[Row]](0)
    assertEquals(0, result(0).size)
  }

  @Test
  def testScriptedUpsert(): Unit = {
    val testIndex = "scripted_upsert_test"
    val updateParams = "count: <4>"
    val updateScript = "if ( ctx.op == 'create' ) {ctx._source.counter = params.count} else {ctx._source.counter += params.count}"
    val conf = Map("opensearch.mapping.id" -> "id", "opensearch.mapping.exclude" -> "id", "opensearch.write.operation" -> "upsert", "opensearch.update.script.params" ->
      updateParams, "opensearch.update.script.upsert" -> "true", "opensearch.update.script.inline" -> updateScript)
    val data = Seq(Row("1", 3))
    val rdd: RDD[Row] = sc.parallelize(data)
    val schema = new StructType().add("id", StringType, nullable = false).add("count", IntegerType, nullable = false)
    val df = sqc.createDataFrame(rdd, schema)
    df.write.format("opensearch").options(conf).mode(SaveMode.Append).save(testIndex)

    val reader = sqc.read.format("opensearch")
    var readerDf = reader.load(testIndex)
    var result = readerDf.select("counter").first().get(0)
    assertEquals(4l, result)

    df.write.format("opensearch").options(conf).mode(SaveMode.Append).save(testIndex)
    readerDf = reader.load(testIndex)
    result = readerDf.select("counter").first().get(0)
    assertEquals(8l, result)
  }

  @Test
  def testNestedFieldsUpsert(): Unit = {
    val update_params = "new_samples: samples"
    val update_script = "ctx._source.samples = params.new_samples"
    val es_conf = Map(
      "opensearch.mapping.id" -> "id",
      "opensearch.write.operation" -> "upsert",
      "opensearch.update.script.params" -> update_params,
      "opensearch.update.script.inline" -> update_script
    )
    // First do an upsert with two completely new rows:
    var data = Seq(Row("2", List(Row("hello"), Row("world"))), Row("1", List()))
    var rdd: RDD[Row] = sc.parallelize(data)
    val schema = new StructType()
      .add("id", StringType, nullable = false)
      .add("samples", new ArrayType(new StructType()
        .add("text", StringType), true))
    var df = sqc.createDataFrame(rdd, schema)
    df.write.format("org.opensearch.spark.sql").options(es_conf).mode(SaveMode.Append).save("nested_fields_upsert_test")

    val reader = sqc.read.schema(schema).format("org.opensearch.spark.sql").option("opensearch.read.field.as.array.include","samples")
    var resultDf = reader.load("nested_fields_upsert_test")
    assertEquals(2, resultDf.count())
    var samples = resultDf.where(resultDf("id").equalTo("2")).select("samples").first().getAs[IndexedSeq[Row]](0)
    assertEquals(2, samples.size)
    assertEquals("hello", samples(0).get(0))
    assertEquals("world", samples(1).get(0))

    //Now, do an upsert on the one with the empty samples list:
    data = Seq(Row("1", List(Row("goodbye"), Row("world"))))
    rdd = sc.parallelize(data)
    df = sqc.createDataFrame(rdd, schema)
    df.write.format("org.opensearch.spark.sql").options(es_conf).mode(SaveMode.Append).save("nested_fields_upsert_test")

    resultDf = reader.load("nested_fields_upsert_test")
    samples = resultDf.where(resultDf("id").equalTo("1")).select("samples").first().getAs[IndexedSeq[Row]](0)
    assertEquals(2, samples.size)
    assertEquals("goodbye", samples(0).get(0))
    assertEquals("world", samples(1).get(0))

    // Finally, an upsert on the row that had samples values:
    data = Seq(Row("2", List(Row("goodbye"), Row("again"))))
    rdd = sc.parallelize(data)
    df = sqc.createDataFrame(rdd, schema)
    df.write.format("org.opensearch.spark.sql").options(es_conf).mode(SaveMode.Append).save("nested_fields_upsert_test")

    resultDf = reader.load("nested_fields_upsert_test")
    samples = resultDf.where(resultDf("id").equalTo("2")).select("samples").first().getAs[IndexedSeq[Row]](0)
    assertEquals(2, samples.size)
    assertEquals("goodbye", samples(0).get(0))
    assertEquals("again", samples(1).get(0))
  }

  @Test
  def testMapsUpsert(): Unit = {
    val update_params = "new_samples: samples"
    val update_script = "ctx._source.samples = params.new_samples"
    val es_conf = Map(
      "opensearch.mapping.id" -> "id",
      "opensearch.write.operation" -> "upsert",
      "opensearch.update.script.params" -> update_params,
      "opensearch.update.script.inline" -> update_script
    )
    // First do an upsert with two completely new rows:
    var data = Seq(Row("2", Map(("hello", "world"))), Row("1", Map()))
    var rdd: RDD[Row] = sc.parallelize(data)
    val schema = new StructType()
      .add("id", StringType, nullable = false)
      .add("samples", new MapType(StringType, StringType, true))
    var df = sqc.createDataFrame(rdd, schema)
    df.write.format("org.opensearch.spark.sql").options(es_conf).mode(SaveMode.Append).save("map_fields_upsert_test")

    val reader = sqc.read.format("org.opensearch.spark.sql")
    var resultDf = reader.load("map_fields_upsert_test")
    assertEquals(2, resultDf.count())
    var samples = resultDf.where(resultDf("id").equalTo("2")).select("samples").first()
    assertEquals(1, samples.size)
    assertEquals("world", samples.get(0).asInstanceOf[Row].get(0))

    //Now, do an upsert on the one with the empty samples list:
    data = Seq(Row("1", Map(("goodbye", "all"))))
    rdd = sc.parallelize(data)
    df = sqc.createDataFrame(rdd, schema)
    df.write.format("org.opensearch.spark.sql").options(es_conf).mode(SaveMode.Append).save("map_fields_upsert_test")

    resultDf = reader.load("map_fields_upsert_test")
    samples = resultDf.where(resultDf("id").equalTo("1")).select("samples").first()
    assertEquals(1, samples.size)
    assertEquals("all", samples.get(0).asInstanceOf[Row].get(0))

    // Finally, an upsert on the row that had samples values:
    data = Seq(Row("2", Map(("goodbye", "again"))))
    rdd = sc.parallelize(data)
    df = sqc.createDataFrame(rdd, schema)
    df.write.format("org.opensearch.spark.sql").options(es_conf).mode(SaveMode.Append).save("map_fields_upsert_test")

    resultDf = reader.load("map_fields_upsert_test")
    samples = resultDf.where(resultDf("id").equalTo("2")).select("samples").first()
    assertEquals(1, samples.size)
    assertEquals("again", samples.get(0).asInstanceOf[Row].get(0))
  }

  /**
   * Take advantage of the fixed method order and clear out all created indices.
   * The indices will last in OpenSearch for all parameters of this test suite.
   * This test suite often puts a lot of stress on the system's available file
   * descriptors due to the volume of indices it creates.
   */
  @Test
  def zzzz_clearEnvironment() {
    // Nuke the whole environment after the tests run.
    RestUtils.delete("_all")
  }
  
  def wrapIndex(index: String) = {
    prefix + index
  }

  def wrapMapping(typeName: String, typelessMapping: String): String = {
    if (TestUtils.isTypelessVersion(version)) {
      typelessMapping
    } else {
      s"""{"$typeName":$typelessMapping}"""
    }
  }

  def makeTargets(index: String, typeName: String): (String, String) = {
    if (TestUtils.isTypelessVersion(version)) {
      (index, s"$index/_doc")
    } else {
      (s"$index/$typeName", s"$index/$typeName")
    }
  }

  /**
   * When using Unicode characters in table names for SparkSQL, they need to be enclosed in backticks.
   */
  def wrapTableName(name: String) = {
    if (escapeResources) "`" + name + "`" else name
  }

  private def keepHandledFilters = {
    !pushDown || (pushDown && doubleFiltering)
  }
}