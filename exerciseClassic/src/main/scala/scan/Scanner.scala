package scan

import java.nio.file._

import scala.compat.java8.StreamConverters._
import scala.collection.SortedSet

import cats._
import cats.implicits._


object Scanner {

  def main(args: Array[String]): Unit = {
    println(scanReport(Paths.get(args(0)), 10))
  }

  def scanReport(base: Path, topN: Int): String = {
    val scan = pathScan(FilePath(base), topN)

    ReportFormat.largeFilesReport(scan, base.toString)
  }

  def pathScan(filePath: FilePath, topN: Int): PathScan = filePath match {
    case File(path) =>
      val fs = FileSize.ofFile(Paths.get(path))
      PathScan(SortedSet(fs), fs.size, 1)
    case Directory(path) =>
      val files = {
        val jstream = Files.list(Paths.get(path))
        try jstream.toScala[List]
        finally jstream.close()
      }
      val subscans = files.map(subpath => pathScan(FilePath(subpath), topN))
      subscans.combineAll(PathScan.topNMonoid(topN))
    case Other(_) =>
      PathScan.empty
  }

}

case class PathScan(largestFiles: SortedSet[FileSize], totalSize: Long, totalCount: Long)

object PathScan {

  def empty = PathScan(SortedSet.empty, 0, 0)

  def topNMonoid(n: Int): Monoid[PathScan] = new Monoid[PathScan] {
    def empty: PathScan = PathScan.empty

    def combine(p1: PathScan, p2: PathScan): PathScan ={
      val files = p1.largestFiles ++ p2.largestFiles
      PathScan(files.take(n), p1.totalSize + p2.totalSize, p1.totalCount + p2.totalCount)
    }
  }

}

case class FileSize(path: Path, size: Long)

object FileSize {

  def ofFile(file: Path) = {
    FileSize(file, Files.size(file))
  }

  implicit val ordering: Ordering[FileSize] = Ordering.by[FileSize, Long](_.size).reverse

}
//I prefer an closed set of disjoint cases over a series of isX(): Boolean tests, as provided by the Java API
//The problem with boolean test methods is they make it unclear what the complete set of possible states is, and which tests
//can overlap
sealed trait FilePath {
  def path: String
}
object FilePath {

  def apply(path: Path): FilePath =
    if (Files.isRegularFile(path))
      File(path.toString)
    else if (Files.isDirectory(path))
      Directory(path.toString)
    else
      Other(path.toString)
}
case class File(path: String) extends FilePath
case class Directory(path: String) extends FilePath
case class Other(path: String) extends FilePath


//Common pure code that is unaffected by the migration to Eff
object ReportFormat {

  def largeFilesReport(scan: PathScan, rootDir: String): String = {
    if (scan.largestFiles.nonEmpty) {
      s"Largest ${scan.largestFiles.size} file(s) found under path: $rootDir\n" +
        scan.largestFiles.map(fs => s"${(fs.size * 100)/scan.totalSize}%  ${formatByteString(fs.size)}  ${fs.path}").mkString("", "\n", "\n") +
        s"${scan.totalCount} total files found, having total size ${formatByteString(scan.totalSize)} bytes.\n"
    }
    else
      s"No files found under path: $rootDir"
  }

  def formatByteString(bytes: Long): String = {
    if (bytes < 1000)
      s"${bytes} B"
    else {
      val exp = (Math.log(bytes) / Math.log(1000)).toInt
      val pre = "KMGTPE".charAt(exp - 1)
      s"%.1f ${pre}B".format(bytes / Math.pow(1000, exp))
    }
  }
}
