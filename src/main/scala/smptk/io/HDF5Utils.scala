package smptk.io

import java.io.File
import ncsa.hdf.`object`._
import ncsa.hdf.`object`.h5._
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import scala.collection.JavaConversions._

case class NDArray[T](dims: IndexedSeq[Long], data: Array[T]) {
  require(dims.reduce(_ * _) == data.length)
}

class HDF5File(h5file: FileFormat) {

  def close() { h5file.close() }

  def exists(path: String): Boolean = h5file.get(path) != null

  def readString(path: String): Try[String] = {
	
    // a string seems to be represented as an array in hdf5
    // we return just the first element
    val stringArrayOrFailure = readNDArray[String](path)
    stringArrayOrFailure.map { stringArray =>
      assert(stringArray.dims.length == 1 && stringArray.dims(0) == 1 && stringArray.data.length == 1)
      stringArray.data.head
    }
  }

  def readStringAttribute(path : String, attrName : String) : Try[String] = { 
    h5file.get(path) match { 
      case s@(_: H5Group | _ : H5ScalarDS) => { 
        val metadata = s.getMetadata()
        val maybeAttr = metadata.find(d => d.asInstanceOf[Attribute].getName().equals(attrName) )
        maybeAttr match { 
          case Some(a) => {
            Success(a.asInstanceOf[Attribute].getValue().asInstanceOf[Array[String]](0))
          }
          case None => Failure(new Exception("Attribute $attrName not found"))
        }
      }

      case _ => { 
        Failure(new Exception("Expected H5ScalarDS when reading attribute"))
      }
    } 
    
  }
  
  def readNDArray[T](path: String): Try[NDArray[T]] = {

    h5file.get(path) match {
      case s: H5ScalarDS => {
        // we need to explicitly set the selectedDims to dims, in order to avoid that 
        // in the three D case only the first slice is read (bug in hdf5?)
        s.read()
        val dims = s.getDims()
        val selectedDims = s.getSelectedDims()
        for (i <- 0 until dims.length) { selectedDims(i) = dims(i) }
        val data = s.getData()

        Try(NDArray(dims.toIndexedSeq, data.asInstanceOf[Array[T]]))

      }
      case _ => {
        Failure(new Exception("Expected H5ScalarDS when reading ND array for key " + path))
      }
    }
  }

  def readArray[T](path: String): Try[Array[T]] = {

    readNDArray[T](path).map { ndArray => 
        assume(ndArray.dims.length == 1)
        ndArray.data
    }
  }

  def writeNDArray[T](path: String, ndArray: NDArray[T]): Try[Unit] = {

    val (groupname, datasetname) = splitpath(path)
    val maybeGroup = createGroup(groupname)
    maybeGroup.map { group =>

      val dtypeOrFailure = ndArray.data match {
        case _: Array[Byte] => {
          Success(h5file.createDatatype(Datatype.CLASS_INTEGER,
            1, Datatype.NATIVE, Datatype.NATIVE))
        }
        case _: Array[Short] => {
          Success(h5file.createDatatype(Datatype.CLASS_INTEGER,
            2, Datatype.NATIVE, Datatype.NATIVE))
        }
        case _: Array[Int] => {
          Success(h5file.createDatatype(Datatype.CLASS_INTEGER,
            4, Datatype.NATIVE, Datatype.NATIVE))
        }
        case _: Array[Long] => {
          Success(h5file.createDatatype(Datatype.CLASS_INTEGER,
            8, Datatype.NATIVE, Datatype.NATIVE))
        }
        case _: Array[Float] => {
          Success(h5file.createDatatype(Datatype.CLASS_FLOAT,
            4, Datatype.NATIVE, Datatype.NATIVE))
        }
        case _: Array[Double] => {
          Success(h5file.createDatatype(Datatype.CLASS_FLOAT,
            8, Datatype.NATIVE, Datatype.NATIVE))
        }
        case _ => {
          // TODO error handling
          Failure(new Exception("unknown type for path " +path))
        }
      }

      val dsOrFailure = dtypeOrFailure.map(dtype => h5file.createScalarDS(datasetname, group, dtype, ndArray.dims.toArray, null, null, 0, ndArray.data))
      dsOrFailure.map(_ => ()) // ignore result and return either failure or unit
    }
  }

  def writeArray[T](path: String, data: Array[T]): Try[Unit] = {
    writeNDArray[T](path, NDArray(Vector[Long](data.length), data))
  }

  def writeString(path: String, value: String): Try[Unit] = {
    val (groupname, datasetname) = splitpath(path)
    val groupOrFailure = createGroup(groupname)

    groupOrFailure.map { group =>
      val dtype = h5file.createDatatype(Datatype.CLASS_STRING,
        value.length, Datatype.NATIVE, Datatype.NATIVE)
      h5file.createScalarDS(datasetname, group, dtype, Array[Long](1), null, null, 0, Array[String](value));
      Success(Unit)
    }
  }

  private def createGroup(parent: Group, relativePath: String): Try[Group] = {

    // remove trailing /
    val trimmedPath =
      if (relativePath.trim().last == '/') {
        relativePath.trim.dropRight(1)
      } else {
        relativePath
      }

    assert(trimmedPath.startsWith("/") == false)
    if (trimmedPath == "") return Success(parent)

    val groupnames = trimmedPath.split("/", 2)

    val newGroupOrNull = h5file.get(parent.getFullName() + "/" + groupnames(0))
    val newgroup = if (newGroupOrNull == null) {
      // the group does not yet exist 
      h5file.createGroup(groupnames(0), parent)
    } else {
      // the group already existed - return it
      newGroupOrNull.asInstanceOf[Group]
    }
    if (groupnames.length == 1) {
      // the path is just the group name, we are done and return
      Success(newgroup)
    } else {
      // otherwise we do a recursive call
      createGroup(newgroup, groupnames(1))
    }

  }

  def createGroup(absolutePath: String): Try[Group] = {

    val root = h5file.getRootNode().
      asInstanceOf[javax.swing.tree.DefaultMutableTreeNode].getUserObject().
      asInstanceOf[Group]

    if (absolutePath.startsWith("/") == false)
      return Failure(new Exception("relative path provieded tocreateGroup: " + absolutePath))
    if (absolutePath.trim == "/")
      return Success(root)

    createGroup(root, absolutePath.stripPrefix("/"))

  }

  private def splitpath(path: String): Tuple2[String, String] = {

    val pos = path.lastIndexOf("/")
    val (groupname, datasetname) = path.splitAt(pos)
    if (groupname == "") ("/", datasetname)
    else (groupname, datasetname)
  }

}

// make it a proper class and wrapper around the object
object HDF5Utils {

  // map untyped FileAccessModel of HDF5 (which is just a string)
  // to typed values
  object FileAccessMode extends Enumeration {
    type FileAccessMode = Value
    val READ, WRITE, CREATE = Value
  }
  import FileAccessMode._

  def hdf5Version = "to be defined"

  def openFile(file: File, mode: FileAccessMode): HDF5File = {

    val filename = file.getAbsolutePath()
    val h5fileAccessMode = mode match {
      case READ => FileFormat.READ
      case WRITE => FileFormat.WRITE
      case CREATE => FileFormat.CREATE
    }
    val fileFormat = FileFormat.getFileFormat(FileFormat.FILE_TYPE_HDF5);
    val h5file = fileFormat.open(filename, h5fileAccessMode);
    h5file.open()
    new HDF5File(h5file)
  }

  def openFileForReading(file: File): HDF5File = openFile(file, READ)
  def openFileForWriting(file: File): HDF5File = openFile(file, WRITE)
  def createFile(file: File): HDF5File = openFile(file, CREATE)

}