package smptk.registration

import smptk.image.Geometry._
import TransformationSpace.ParameterVector
import breeze.linalg.DenseMatrix
import scala.NotImplementedError
import breeze.linalg.DenseVector
import smptk.image.CoordVector
import smptk.image.DiscreteImageDomain1D
import breeze.plot._
import smptk.image.DiscreteImageDomain1D
import smptk.image.Geometry.implicits._
import smptk.image.Image._
import smptk.image.DiscreteScalarImage1D
import smptk.image.Interpolation
import smptk.image.Utils


trait GaussianProcess[CV[A] <: CoordVector[A]] {
  val  m : (CV[Double] => Double)
  val k : PDKernel[CV]

  def sample: IndexedSeq[CV[Double]] => DenseVector[Double]
}

case class GaussianProcess1D(val m: CoordVector1D[Double] => Double, val k: PDKernel[CoordVector1D]) extends GaussianProcess[CoordVector1D] {

  type Sample1D = IndexedSeq[CoordVector1D[Double]]


  def posterior(trainingData: IndexedSeq[(CoordVector1D[Double], Double)], sigma2: Double): GaussianProcess1D = {

    val (xs, ys) = trainingData.unzip
    val yVec = DenseVector(ys.toArray)
    val kxx = Kernel.computeKernelMatrix(xs, k)

    val kinv = breeze.linalg.inv(kxx + DenseMatrix.eye[Double](xs.size) * sigma2)

    def mp(x: CoordVector1D[Double]): Double = {
      val kxs = Kernel.computeKernelVectorFor(x, xs,k)
      (kxs dot kinv * yVec)

    }

    val kp = new PDKernel[CoordVector1D] {
      def apply(x1: Point1D, x2: Point1D) = {
        val kx1xs = Kernel.computeKernelVectorFor(x1, xs, k)
        val kx2xs = Kernel.computeKernelVectorFor(x2, xs, k)
        k(x1, x2) - (kx1xs dot (kinv * kx2xs))
      }
    }
    GaussianProcess1D(mp, kp)
  }

  def sample: (Sample1D => DenseVector[Double]) = { (xs: Sample1D) =>
    {
      val meanVec = DenseVector(xs.map(cv => m(cv)).toArray)
      val covMatrix = Kernel.computeKernelMatrix(xs, k)
      val noise = breeze.linalg.diag(DenseVector.ones[Double](xs.size)) * 1e-6 // gaussian noise for stability 
      val lMat = breeze.linalg.cholesky(covMatrix + noise)
      val u = for (_ <- 0 until xs.size) yield breeze.stats.distributions.Gaussian(0, 1).draw()
      val uVec = DenseVector(u.toArray)
      meanVec + lMat * uVec
    }
  }

}

case class KernelTransformationSpace1D(val domain: DiscreteImageDomain1D,
  val numParameters: Int, gp: GaussianProcess[CoordVector1D]) extends TransformationSpace[CoordVector1D] {

  def parametersDimensionality = numParameters

  val eigenPairs = Kernel.computeNystromApproximation(gp.k, domain, numParameters)

  def apply(p: ParameterVector) = KernelTransformation1D(p)
  def inverseTransform(p: ParameterVector) = None
  def takeDerivativeWRTParameters(p: ParameterVector) = { x: Point1D =>
    
    val terms = for ((lambda, phi) <- eigenPairs) yield (math.sqrt(lambda) * phi(x))
    DenseMatrix.create(1, numParameters, terms.toArray)
  }

  private def computeKernelVectorFor(x: Point1D, pts: IndexedSeq[Point1D]): DenseVector[Double] = {
    val k = DenseVector.zeros[Double](pts.size)
    for ((pt_i, i) <- pts.zipWithIndex) {
      k(i) = gp.k(x, pt_i)
    }
    k
  }



  private def computeKernelMatrix(pts: IndexedSeq[Point1D]): DenseMatrix[Double] = {

    val k = DenseMatrix.zeros[Double](pts.size, pts.size)
    for ((pt_i, i) <- pts.zipWithIndex; (pt_j, j) <- pts.zipWithIndex) {
      k(i, j) = gp.k(pt_i, pt_j)
      k(j, i) = k(i, j)
    }
    k
  }

  // the actual kernel transform
  case class KernelTransformation1D(alpha: ParameterVector) extends Transformation[CoordVector1D] {
    require(alpha.size == eigenPairs.size)
    
    def apply(x: Point1D) = {
      val terms = for (((lambda, phi), i) <- eigenPairs.zipWithIndex view) yield (alpha(i) * math.sqrt(lambda) * phi(x))
      CoordVector1D(x(0) + terms.sum + gp.m(x))
    }

    def takeDerivative(x: Point1D) = { throw new NotImplementedError("take derivative of kernel") }
  }

}



object KernelTransformationSpace {
  def main(args: Array[String]) {

    
    
    val domain = DiscreteImageDomain1D(-5., 0.1,  100)
      val discreteImage = DiscreteScalarImage1D(domain, domain.points.map(x => x(0)))
      val continuousImg = Interpolation.interpolate(3)(discreteImage)

      
      val gk = PolynomialKernel1D(5)
      val gp = GaussianProcess1D((x : Point1D) => 0., gk)
      val kernelSpace = KernelTransformationSpace1D(domain, 5, gp)
      
      val transform = kernelSpace(DenseVector.ones[Double](5))
      val transformedImg = continuousImg compose transform
      
//      val f = Figure()
//      val p = f.subplot(0)
//      
//      val xs = domain.points
//      val eigPairs = Kernel.computeNystromApproximation(gp.k, domain, 5)
//      for ((lmbda, phi) <- eigPairs) { 
//    	  p += plot(xs.map(_(0)), xs.map(x => phi(x)))
//      }
//	  f.refresh      
//      Utils.showGrid1D(domain, transform)
      
      val regResult = Registration.registration1D(transformedImg, continuousImg, kernelSpace, MeanSquaresMetric1D, 
      0f, DenseVector(1.,1.,0.,1.,1.))
      
      Utils.show1D(continuousImg, domain)
      Utils.show1D(transformedImg, domain)
      Utils.show1D(transformedImg compose regResult(domain).transform , domain)
    
    
    
//   val N = 100
//      //val kernel = PolynomialKernel1D(3)
//      val kernel = GaussianKernel1D(10)
//      val domain = DiscreteImageDomain1D(CoordVector1D(0f), CoordVector1D(0.01f), CoordVector1D(500))
//      val eigenPairs = Kernel.computeNystromApproximation(kernel, domain, 100)
//      
//      def approxKernel(x : Point1D, y : Point1D) = {   
//        eigenPairs.foldLeft(0.)((sum, eigenPair) => {
//          val (lmbda, phi) = eigenPair
//          sum + lmbda * phi(x) * phi(y)
//        })
//      }
//      
//      for (x<-domain.points; y <-domain.points) {
// 
//        approxKernel(x,y)
//        
//      }
      
//    val kernel = GaussianKernel1D(2)
//
//    //val kernel = PolynomialKernel1D(4)
//
//    val gp = GaussianProcess1D((_: Point1D) => 0., kernel)
//
//    val domain = DiscreteImageDomain1D(CoordVector1D(0f), CoordVector1D(0.01f), CoordVector1D(500))
//    val ts = KernelTransformationSpace1D(domain, 20, gp)
//
//    val f = Figure()
//    val p = f.subplot(0)
//
//    val xs = domain.points
//
//    val trainingData = IndexedSeq((CoordVector1D(0.5), 1.), (CoordVector1D(2.), -1.), (CoordVector1D(3.5), -1.5))
//    val gpp = gp.posterior(trainingData, 0.000001)
//    for (i <- 0 until 5) {
//      val sample = gpp.sample(xs)
//      p += plot(xs.map(_(0)).toArray, sample)
//    }
//    val meanSample = xs.map(gpp.m)
//    p += plot(xs.map(_(0)).toArray, DenseVector(meanSample.toArray), '.')
//    f.saveas("/tmp/plot.png")
  }
}
