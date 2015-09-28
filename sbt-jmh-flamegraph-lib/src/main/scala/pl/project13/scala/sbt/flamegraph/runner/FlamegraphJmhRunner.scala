package pl.project13.scala.sbt.flamegraph.runner

import java.io.{File, BufferedOutputStream, FileOutputStream, InputStream}
import java.nio.file.{StandardCopyOption, CopyOption, Files}
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute}
import java.util

import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.CommandLineOptions

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.sys.process._

object FlamegraphJmhRunner
  extends JvmProcessSupport
  with PerfSupport
  with Logging {

  // good enough for us
  override implicit def ec = ExecutionContext.Implicits.global

  def main(args: Array[String]): Unit = {
    val opts = new CommandLineOptions(args: _*) // parse command line arguments, and then bend them to your will! ;-)

    preparePerfJavaFlamesScripts()
    preparePerfJavaFlamesLibs()

    val runner = new Runner(opts) // full access to all JMH features, you can also provide a custom output Format here

    // TODO support multiple forks, need PID monitoring
    val (resultsFuture, pid) = detectJvmProcessId {
      runner.run() // actually run the benchmarks
    }

    // TODO figure out benchmark name somehow?
    val output = new File("out.svg")
    val flamesProcess = attachPerfJavaFlames(pid, output)

    resultsFuture onComplete {
      case _ =>
        log(s"Stopping moinitoring of $pid...")
        flamesProcess.kill()
    }

    log("Awaiting benchmark results...")
    val results = Await.result(resultsFuture, 1.minute)
    System.exit(0)
  }

}

trait JvmProcessSupport {

  implicit def ec: ExecutionContext

  def detectJvmProcessId(block: => util.Collection[RunResult]): (Future[List[RunResult]], Long) = {
    val pidsBefore = jps()
    val results = Future {
      // run benchmarks
      block.asScala.toList
    }
    val forkedPid = findForkedMain(pidsBefore)
    results -> forkedPid
  }

  @tailrec final def findForkedMain(before: Set[String], attempts: Int = 5): Long = {
    // TODO technically not needed (the diffing)
    val now = jps()
    (now -- before).filter(_.contains("ForkedMain")).toList match {
      case process :: Nil =>
        process.split(" ").head.toLong

      case Nil =>
        if (attempts > 0) findForkedMain(before, attempts - 1)
        else throw new Exception("Unable to find JMH ForkedMain process! Before PIDs were: \n" + before)

      case more =>
        if (attempts > 0) findForkedMain(before, attempts - 1)
        else throw new Exception("Unable to find JMH ForkedMain process! " +
          "Multiple ForkedMain found, do not run many benchmarks as the same time!" +
          "Found: \n" + more)
    }
  }

  def jps(): Set[String] = {
    import scala.sys.process._
    """jps -mlV""".!!.split("\n").toSet
  }
}


trait PerfSupport extends Logging {
  implicit def ec: ExecutionContext

  private val PerfJavaFlamesScriptName = "perf-java-flames"
  private val ScriptDir = new File(s"/tmp/sbt-jmh-perf-flames")

  // env vars
  val FlamegraphDir = "FLAMEGRAPH_DIR"

  def preparePerfJavaFlamesScripts(): Unit = {
    if (!ScriptDir.exists())
      Files.createDirectory(ScriptDir.toPath)
    if (!new File(ScriptDir, "bin").exists())
      Files.createDirectory(new File(ScriptDir, "bin").toPath)

    List("create-java-perf-map.sh",
      "perf-java-flames",
      "perf-java-record-stack",
      "perf-java-report-stack",
      "perf-java-top") foreach { script =>
      val target = new File(ScriptDir.toString + "/bin", script)
      if (target.exists() && target.canExecute) ()
      else {
        target.delete()
        try {
          val is = getClass.getClassLoader.getResourceAsStream(s"bin/$script")
          Files.copy(is, target.toPath, StandardCopyOption.REPLACE_EXISTING) // TODO avoid JDK7 dependency? maybe meh, since flames make sense on 8+
          target.setExecutable(true)
          log(s"Prepared $script")
        } catch {
          case ex: Exception =>
            throw new RuntimeException(s"Unable to extract bin/$script!", ex)
        }
      }
    }
  }

  def preparePerfJavaFlamesLibs(): Unit = {
    if (!ScriptDir.exists())
      Files.createDirectory(ScriptDir.toPath)

    // TODO compile 32bit, and emit the one we need only
    List("libperfmap-64bit.so") foreach { file =>
      val target = new File(ScriptDir, file)
      if (target.exists()) ()
      else {
        try {
          val is = getClass.getClassLoader.getResourceAsStream(s"$file")
          Files.copy(is, target.toPath) // TODO avoid JDK7 dependency? maybe meh, since flames make sense on 8+
        } catch {
          case ex: Exception =>
            throw new RuntimeException(s"Unable to extract $file!", ex)
        }
      }
    }
  }

  def attachPerfJavaFlames(pid: Long, svgOut: File): PerfProcess = {
    log(s"Attaching perf to jvm process: ${Console.BOLD}$pid${Console.RESET}")
    import scala.sys.process._

    val env =
      ("PERF_FLAME_OUTPUT", svgOut.toString) ::
      (FlamegraphDir, sys.env.getOrElse(FlamegraphDir, "/tmp/sbt-jmh-flamegraph-Flamegraph")) ::
      Nil

    val cmd = s"""./$PerfJavaFlamesScriptName $pid"""
    val p = Process(cmd, new File(ScriptDir, "bin"), env: _*).run(ProcessLogger(log, log), connectInput = true)
    PerfProcess(p)
  }
}

final case class PerfProcess(process: Process) {
  def kill(): Unit = process.destroy()
}

trait Logging {
  def log(msg: String): Unit = {
    println(s"${Console.RED}[sbt-jmh-flamegraph]${Console.RESET} $msg")
    System.out.flush()
  }
}