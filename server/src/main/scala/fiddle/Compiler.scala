package fiddle
import acyclic.file
import scala.tools.nsc.Settings
import java.net.URLClassLoader
import scala.reflect.io
import scala.tools.nsc.util.ClassPath
import java.io._
import akka.util.ByteString
import scala.tools.nsc.reporters.ConsoleReporter
import scala.tools.nsc.plugins.Plugin
import scala.concurrent.Future

import scala.reflect.internal.util.{BatchSourceFile, OffsetPosition}
import scala.tools.nsc.interactive.Response
import java.util.zip.ZipInputStream
import scala.io.Source
import scala.scalajs.tools.optimizer.{ScalaJSClosureOptimizer, ScalaJSOptimizer}
import scala.scalajs.tools.io.{VirtualScalaJSClassfile, VirtualFile, VirtualJSFile}
import scala.scalajs.tools.logging.Level
import scala.scalajs.tools.classpath.ScalaJSClasspathEntries

object Compiler{

  val validJars = Seq(
    "/classpath/scalajs-library_2.10-0.4.2-SNAPSHOT.jar",
    "/classpath/scalajs-dom_2.10-0.3.jar",
    "/classpath/scalatags_2.10-0.2.4-JS.jar",
    "/classpath/scalarx_2.10-0.2.3-JS.jar",
    "/page_2.10-0.1-SNAPSHOT.jar"
  )

  val classPath = {
    val builder = new ScalaJSClasspathEntries.Builder 
    for(name <- Compiler.validJars){
      val stream = getClass.getResourceAsStream(name)
      assert(stream != null, s"stream for $name is null")
      ScalaJSClasspathEntries.readEntriesInJar(
        builder,
        getClass.getResourceAsStream(name)
      )
    }
    builder.result  
  }

  def prep(virtualFiles: Seq[(String, String)]) = {
    val jsFiles = virtualFiles.filter(_._1.endsWith(".js")).toMap
    val jsInfoFiles = virtualFiles.filter(_._1.endsWith(".sjsinfo")).toMap
    val jsKeys = jsFiles.keys.map(_.dropRight(".js".length)).toSet
    val jsInfoKeys = jsInfoFiles.keys.map(_.dropRight(".sjsinfo".length)).toSet
    val sharedKeys = jsKeys.intersect(jsInfoKeys)
    val scalaJsFiles = for(key <- sharedKeys.toSeq) yield new VirtualScalaJSClassfile {
      def name = key + ".js"
      def content = jsFiles(key + ".js")
      def info = jsInfoFiles(key + ".sjsinfo")
    }
    (jsFiles, jsInfoFiles, scalaJsFiles)
  }
  
  import concurrent.ExecutionContext.Implicits.global
  val blacklist = Seq(
    "<init>"
  )
  val prelude = Source.fromInputStream(getClass.getResourceAsStream("/Prelude.scala")).mkString

  def toFuture[T](func: Response[T] => Unit): Future[T] = {
    val r = new Response[T]
    Future { func(r) ; r.get.left.get }
  }
  import scala.async.Async.{async, await}

  def autocomplete(code: String, flag: String, pos: Int, classpath: Seq[String]): Future[List[String]] = async {
    val vd = new io.VirtualDirectory("(memory)", None)
    // global can be reused, just create new runs for new compiler invocations
    val compiler = initGlobal(
      (settings, reporter) => new scala.tools.nsc.interactive.Global(settings, reporter),
      vd,
      classpath,
      s => ()
    )

    val file      = new BatchSourceFile(makeFile(prelude.getBytes ++ code.getBytes), prelude + code)
    val position  = new OffsetPosition(file, pos + prelude.length)

    await(toFuture[Unit](compiler.askReload(List(file), _)))
    val maybeMems = flag match{
      case "scope" => await(toFuture[List[compiler.Member]](compiler.askScopeCompletion(position, _)))
      case "member" => await(toFuture[List[compiler.Member]](compiler.askTypeCompletion(position, _)))
    }

    compiler.ask(() =>
      maybeMems.map(x => x.sym.decodedName)
               .filter(x => !blacklist.contains(x))
               .distinct
    )
  }

  def makeFile(src: Array[Byte]) = {
    val singleFile = new io.VirtualFile("Main.scala")
    val output = singleFile.output
    output.write(src)
    output.close()
    singleFile
  }
  def initGlobal[T](make: (Settings, ConsoleReporter) => T,
                    vd: io.VirtualDirectory,
                    classpath: Seq[String],
                    logger: String => Unit) = {
    lazy val settings = new Settings
    val loader = getClass.getClassLoader.asInstanceOf[URLClassLoader]
    val entries = loader.getURLs.map(_.getPath)
    val classpathEntries = classpath.map(getClass.getResource(_).getPath.replace("%20", " "))
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = ClassPath.join(classpathEntries ++ entries: _*)

    val writer = new Writer{
      var inner = ByteString()
      def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
        inner = inner ++ ByteString.fromArray(cbuf.map(_.toByte), off, len)
      }
      def flush(): Unit = {
        logger(inner.utf8String)
        inner = ByteString()
      }
      def close(): Unit = ()
    }
    val reporter = new ConsoleReporter(settings, scala.Console.in, new PrintWriter(writer))
    make(settings, reporter)

  }
  def compile(src: Array[Byte], classpath: Seq[String], logger: String => Unit): Option[Seq[io.AbstractFile]] = {

    val singleFile = makeFile(src)
    val vd = new io.VirtualDirectory("(memory)", None)
    val compiler = initGlobal(
      (settings, reporter) => new scala.tools.nsc.Global(settings, reporter){
        override lazy val plugins = List[Plugin](new scala.scalajs.compiler.ScalaJSPlugin(this))
      },
      vd,
      classpath,
      logger
    )
    val run = new compiler.Run()
    run.compileFiles(List(singleFile))

    if (vd.iterator.isEmpty) None
    else Some(vd.iterator.toSeq)
  }
  def extdeps () = {
    import scala.scalajs.tools.packager.ScalaJSPackager
    val packager = new ScalaJSPackager
    val stringer = new StringWriter()
    val printer = new PrintWriter(stringer)

    packager.packageScalaJS(
      ScalaJSPackager.Inputs(classPath),
      ScalaJSPackager.OutputConfig("extdeps.js", printer, None),
      IgnoreLogger
    )
    stringer.toString
  }
  
  def deadCodeElimination(userFiles: Seq[(String, String)]) = {

    val (_, _, preppedUserFiles) = prep(userFiles)

    val res = new ScalaJSOptimizer().optimize(
      ScalaJSOptimizer.Inputs(
        classPath.copy(classFiles = classPath.classFiles ++ preppedUserFiles)
      ),
      ScalaJSOptimizer.OutputConfig("output.js"),
      Compiler.IgnoreLogger
    )
    res.output.content
  }

  def optimize(userFiles: Seq[(String, String)]) = {
    new ScalaJSClosureOptimizer().optimize(
      ScalaJSClosureOptimizer.Inputs(
        Seq(new VirtualJSFile {
          def name = "Hello.js"
          def content = Compiler.deadCodeElimination(userFiles)
        })
      ),
      ScalaJSClosureOptimizer.OutputConfig(
        name = "Hello-opt.js"
      ),
      Compiler.IgnoreLogger
    ).output.content
  }

  object Logger extends scala.scalajs.tools.logging.Logger {
    def log(level: Level, message: => String): Unit = println(message)
    def success(message: => String): Unit = println(message)
    def trace(t: => Throwable): Unit = t.printStackTrace()
  }
  object IgnoreLogger extends scala.scalajs.tools.logging.Logger {
    def log(level: Level, message: => String): Unit = ()
    def success(message: => String): Unit = ()
    def trace(t: => Throwable): Unit = ()
  }
}
