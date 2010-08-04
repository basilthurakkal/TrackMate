package fiji.plugin.nperry;

import fiji.plugin.nperry.scoring.AverageScoreAggregator;
import fiji.plugin.nperry.scoring.BlobBrightnessScorer;
import fiji.plugin.nperry.scoring.BlobContrastScorer;
import fiji.plugin.nperry.scoring.BlobVarianceScorer;
import fiji.plugin.nperry.scoring.LoGScorer;
import fiji.plugin.nperry.scoring.OverlapScorer;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.gui.PointRoi;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.StackConverter;
import ij3d.Content;
import ij3d.ContentInstant;
import ij3d.Image3DMenubar;
import ij3d.Image3DUniverse;
//import ij3d.Executer.SliderAdjuster;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.ListIterator;

import javax.swing.JSlider;

import vib.PointList;

import mpicbg.imglib.algorithm.fft.FourierConvolution;
import mpicbg.imglib.algorithm.extremafinder.RegionalExtremaFactory;
import mpicbg.imglib.algorithm.extremafinder.RegionalExtremaFinder;
import mpicbg.imglib.algorithm.gauss.DownSample;
import mpicbg.imglib.algorithm.gauss.GaussianConvolutionRealType;
import mpicbg.imglib.algorithm.laplace.LoGKernelFactory;
import mpicbg.imglib.algorithm.math.MathLib;
import mpicbg.imglib.algorithm.roi.DirectConvolution;
import mpicbg.imglib.algorithm.roi.MedianFilter;
import mpicbg.imglib.algorithm.roi.StructuringElement;

import mpicbg.imglib.container.array.ArrayContainerFactory;
import mpicbg.imglib.cursor.Cursor;
import mpicbg.imglib.cursor.LocalizableByDimCursor;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.ImageFactory;
import mpicbg.imglib.image.ImagePlusAdapter;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.outofbounds.OutOfBoundsStrategyMirrorFactory;
import mpicbg.imglib.type.logic.BitType;
import mpicbg.imglib.type.numeric.RealType;
import mpicbg.imglib.type.numeric.integer.ShortType;
import mpicbg.imglib.type.numeric.real.FloatType;

/**
 * 
 * @author Nick Perry
 *
 * @param <T>
 */
public class Embryo_Tracker<T extends RealType<T>> implements PlugIn {
	/** Class/Instance variables */
	protected int numDim;
	final static protected float GOAL_DOWNSAMPLED_BLOB_DIAM = 10f;				  // trial and error showed that downsizing images so that the blobs have a diameter of 10 pixels performs best (least errors, and most correct finds, by eyeball analysis).
	final static protected double IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM = 1.55f;  // trial and error proved this to be approximately the best sigma for a blob of 10 pixels in diameter.
	
	/** Ask for parameters and then execute. */
	@SuppressWarnings("unchecked")
	public void run(String arg) {
		/* 1 - Obtain the currently active image */
		ImagePlus ip = IJ.getImage();
		if (null == ip) return;
		
		/* 2 - Ask for parameters */
		GenericDialog gd = new GenericDialog("Track");
		gd.addNumericField("Generic blob diameter:", 7.3, 2, 5, ip.getCalibration().getUnits());  	// get the expected blob size (in pixels).
		gd.addMessage("Verify calibration settings:");
		gd.addNumericField("Pixel width:", ip.getCalibration().pixelWidth, 3);		// used to calibrate the image for 3D rendering
		gd.addNumericField("Pixel height:", ip.getCalibration().pixelHeight, 3);	// used to calibrate the image for 3D rendering
		gd.addNumericField("Voxel depth:", ip.getCalibration().pixelDepth, 3);		// used to calibrate the image for 3D rendering
		gd.addCheckbox("Use median filter", false);
		gd.addCheckbox("Allow edge maxima", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		/* 3 - Retrieve parameters from dialogue */
		double diam = (float)gd.getNextNumber();
		double pixelWidth = (double)gd.getNextNumber();
		double pixelHeight = (double)gd.getNextNumber();
		double pixelDepth = (double)gd.getNextNumber();
		boolean useMedFilt = (boolean)gd.getNextBoolean();
		boolean allowEdgeMax = (boolean)gd.getNextBoolean();
		double[] calibration = new double[] {pixelWidth, pixelHeight, pixelDepth};

		/* 4 - Execute! */
		Object[] result = exec(ip, diam, useMedFilt, allowEdgeMax, calibration);
		System.out.println("Done executing!");	
		
		/* 5 - Display new image and overlay maxima */
		if (null != result) {
			System.out.println("Rendering...!");
			ArrayList< ArrayList<Spot> > extremaAllFrames = (ArrayList< ArrayList<Spot> >) result[0];
			ArrayList<Double> thresholdsAllFrames = (ArrayList<Double>) result[1];
			if (numDim == 3) {	// If original image is 3D, create a 3D rendering of the image and overlay maxima
				renderIn3DViewer(extremaAllFrames, thresholdsAllFrames, ip, calibration, diam);
			} else {
				//PointRoi roi = (PointRoi) result[0];
				//imp.setRoi(roi);
				//imp.updateAndDraw();
			}
		}
	}
	
	/** Execute the plugin functionality: apply a median filter (for salt and pepper noise, if user requests), a Gaussian blur, and then find maxima. */
	public Object[] exec(ImagePlus ip, double diam, boolean useMedFilt, boolean allowEdgeMax, double[] calibration) {		
		/* 0 - Check validity of parameters, initialize local variables */
		if (null == ip) return null;
		ArrayList< ArrayList <Spot> > extremaAllFrames = new ArrayList< ArrayList <Spot> >();
		ArrayList<Double> frameThresholds = new ArrayList<Double>();  // holds the thresholds for each frame's extrema.
		final double downsampleFactors[] = createDownsampledDim(calibration, diam, numDim);	// factors for x,y,z that we need for scaling image down;
		                        
		/* 1 - Create separate ImagePlus's for each frame */
		ImageStack stack = ip.getImageStack();
		int numSlices = ip.getNSlices();
		int numFrames = ip.getNFrames();
		
		// For each frame...
		for (int i = 0; i < numFrames; i++) {
			ImageStack frame = ip.createEmptyStack();
			
			// ...create the slice by combining the ImageProcessors, one for each Z in the stack.
			for (int j = 1; j <= numSlices; j++) {
				frame.addSlice(Integer.toString(j + (i * numSlices)), stack.getProcessor(j + (i * numSlices)));
			}
			ImagePlus ipSingleFrame = new ImagePlus("Frame " + Integer.toString(i + 1), frame);
			
			/* 2 - Prepare stack for use with Imglib. */
			System.out.println();
			IJ.log("---Frame " + Integer.toString(i+1) + "---");
			System.out.println("---Frame " + (i+1) + "---");
			Image<T> img = ImagePlusAdapter.wrap(ipSingleFrame);
			Image<T> modImg = img.clone();
			numDim = img.getNumDimensions();
		
			/* 3 - Downsample to improve run time. The image is downsampled by the factor necessary to achieve a resulting blob size of about 10 pixels in diameter in all dimensions. */
			IJ.log("Downsampling...");
			IJ.showStatus("Downsampling...");
			final int dim[] = img.getDimensions();
			for (int j = 0; j < dim.length; j++) {
				dim[j] = (int) (dim[j] / downsampleFactors[j]);
			}
			final DownSample<T> downsampler = new DownSample<T>(modImg, dim, 0.5f, 0.5f);	// optimal sigma is defined by 0.5f, as mentioned here: http://pacific.mpi-cbg.de/wiki/index.php/Downsample
			if (!downsampler.checkInput() || !downsampler.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
				System.out.println(downsampler.getErrorMessage()); 
		        System.out.println("Bye.");
		        return null;
			}
			modImg = downsampler.getResult(); 
			
			/* 4 - Apply a median filter, to get rid of salt and pepper noise which could be mistaken for maxima in the algorithm (only applied if requested by user explicitly) */
			if (useMedFilt) {
				IJ.log("Applying median filter...");
				IJ.showStatus("Applying median filter...");
				StructuringElement strel;
				
				// Need to figure out the dimensionality of the image in order to create a StructuringElement of the correct dimensionality (StructuringElement needs to have same dimensionality as the image):
				if (numDim == 3) {  // 3D case
					strel = new StructuringElement(new int[]{3, 3, 1}, "3D Square");  // unoptimized shape for 3D case. Note here that we manually are making this shape (not using a class method). This code is courtesy of Larry Lindsey
					Cursor<BitType> c = strel.createCursor();  // in this case, the shape is manually made, so we have to manually set it, too.
					while (c.hasNext()) 
					{ 
					    c.fwd(); 
					    c.getType().setOne(); 
					} 
					c.close(); 
				} else {  			// 2D case
					strel = StructuringElement.createCube(2, 3);  // unoptimized shape
				}
				
				// Apply the median filter:
				final MedianFilter<T> medFilt = new MedianFilter<T>(modImg, strel, new OutOfBoundsStrategyMirrorFactory<T>()); 
				/** note: add back medFilt.checkInput() when it's fixed */
				if (!medFilt.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
					System.out.println(medFilt.getErrorMessage()); 
			        System.out.println("Bye.");
			        return null;
				} 
				modImg = medFilt.getResult(); 
			}
			
			// #---------------------------------------#
			// #------        Time Trials       -------#
			// #---------------------------------------#
			long overall = 0;
			final long numIterations = 1;
			for (int k = 0; k < numIterations; k++) {
				long startTime = System.currentTimeMillis();	
			/** Approach 1: L x (G x I ) */
			/* 5 - Apply a Gaussian filter (code courtesy of Stephan Preibisch). Theoretically, this will make the center of blobs the brightest, and thus easier to find: */
			/*IJ.log("Applying Gaussian filter...");
			IJ.showStatus("Applying Gaussian filter...");
			final GaussianConvolutionRealType<T> convGaussian = new GaussianConvolutionRealType<T>(modImg, new OutOfBoundsStrategyMirrorFactory<T>(), IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM);
			if (convGaussian.checkInput() && convGaussian.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
				modImg = convGaussian.getResult(); 
			} else { 
		        System.out.println(convGaussian.getErrorMessage()); 
		        System.out.println("Bye.");
		        return null;
			}
			
			// 6 - Apply a Laplacian convolution to the image.
			IJ.log("Applying Laplacian convolution...");
			IJ.showStatus("Applying Laplacian convolution...");
			// Laplacian kernel construction: everything is negative so that we can use the existing find maxima classes (otherwise, it would be creating minima, and we would need to use find minima). The kernel has everything divided by 18 because we want the highest value to be 1, so that numbers aren't created that beyond the capability of the image type. For example, in a short type, if we had the highest number * 18, the short type can't hold that, and the rest of the value is lost in conversion. This way, we won't create numbers larger than the respective types can hold.
			DirectConvolution<T, FloatType, T> convLaplacian;
			if (numDim == 3) {
				float laplacianArray[][][] = new float[][][]{ { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} }, { {-1/18,-1/18,-1/18}, {-1/18,1,-1/18}, {-1/18,-1/18,-1/18} }, { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} } }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
				ImageFactory<FloatType> factory = new ImageFactory<FloatType>(new FloatType(), new ArrayContainerFactory());
				Image<FloatType> laplacianKernel = factory.createImage(new int[]{3, 3, 3}, "Laplacian");
				quickKernel3D(laplacianArray, laplacianKernel);
				convLaplacian = new DirectConvolution<T, FloatType, T>(img.createType(), modImg, laplacianKernel);;
			} else {
				float laplacianArray[][] = new float[][]{ {-1/8,-1/8,-1/8},{-1/8,1,-1/8},{-1/8,-1/8,-1/8} }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
				ImageFactory<FloatType> factory = new ImageFactory<FloatType>(new FloatType(), new ArrayContainerFactory());
				Image<FloatType> laplacianKernel = factory.createImage(new int[]{3, 3}, "Laplacian");
				quickKernel2D(laplacianArray, laplacianKernel);
				convLaplacian = new DirectConvolution<T, FloatType, T>(img.createType(), modImg, laplacianKernel);;
			}
			//if(convLaplacian.checkInput() && convLaplacian.process()) {
			if(convLaplacian.process()) {
				modImg = convLaplacian.getResult();
				//ImagePlus test = ImageJFunctions.copyToImagePlus(img);
				//test.show();
			} else {
				System.out.println(convLaplacian.getErrorMessage());
				System.out.println("Bye.");
				return null;
			}*/
			
			/** Approach 2: F(L) x F(G) x F(I) */
			
			// Gauss
			IJ.log("Applying Gaussian filter...");
			IJ.showStatus("Applying Gaussian filter...");
			final ImageFactory<FloatType> factory = new ImageFactory<FloatType>(new FloatType(), new ArrayContainerFactory());
			final Image<FloatType> gaussKernel = FourierConvolution.getGaussianKernel(factory, IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM, numDim);
			final FourierConvolution<T, FloatType> fConvGauss = new FourierConvolution<T, FloatType>(modImg, gaussKernel);
			if (!fConvGauss.checkInput() || !fConvGauss.process()) {
				System.out.println( "Fourier Convolution failed: " + fConvGauss.getErrorMessage() );
				return null;
			}
			modImg = fConvGauss.getResult();
			
			// Laplace
			IJ.log("Applying Laplacian convolution...");
			IJ.showStatus("Applying Laplacian convolution...");
			Image<FloatType> laplacianKernel;
			if (numDim == 3) {
				final float laplacianArray[][][] = new float[][][]{ { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} }, { {-1/18,-1/18,-1/18}, {-1/18,1,-1/18}, {-1/18,-1/18,-1/18} }, { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} } }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
				laplacianKernel = factory.createImage(new int[]{3, 3, 3}, "Laplacian");
				quickKernel3D(laplacianArray, laplacianKernel);
			} else {
				final float laplacianArray[][] = new float[][]{ {-1/8,-1/8,-1/8},{-1/8,1,-1/8},{-1/8,-1/8,-1/8} }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
				laplacianKernel = factory.createImage(new int[]{3, 3}, "Laplacian");
				quickKernel2D(laplacianArray, laplacianKernel);
			}
			final FourierConvolution<T, FloatType> fConvLaplacian = new FourierConvolution<T, FloatType>(modImg, laplacianKernel);
			if (!fConvLaplacian.checkInput() || !fConvLaplacian.process()) {
				System.out.println( "Fourier Convolution failed: " + fConvLaplacian.getErrorMessage() );
				return null;
			}
			modImg = fConvLaplacian.getResult();	
			
			/** Approach 3: (L x G) x I */
			/*IJ.log("Applying LoG Convolution...");
			Image<FloatType> logKern = LoGKernelFactory.createLoGKernel(IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM, numDim, true, true);
			DirectConvolution<T, FloatType, T> convLoG = new DirectConvolution<T, FloatType, T>(img.createType(), img, logKern);
			//DirectConvolution<T, FloatType, FloatType> convLoG = new DirectConvolution<T, FloatType, FloatType>(new FloatType(), img, logKern);
			if(convLoG.process()) {
				imgResult = convLoG.getResult();
				ImagePlus test = ImageJFunctions.copyToImagePlus(imgResult);
				test.show();
			} else {
				System.out.println(convLoG.getErrorMessage());
				System.out.println("Bye.");
				return null;
			}*/
			
			long runTime = System.currentTimeMillis() - startTime;	
			System.out.println("Laplacian/Gaussian Run Time: " + runTime);
			
			
			/** Approach 4: DoG */
			/** Approach 5: F(DoG) */
				overall += runTime;
			}
			System.out.println("Average run time: " + (long)overall/numIterations);
			// #----------------------------------------#
			// #------        /Time Trials       -------#
			// #----------------------------------------#
			
			
			/* 7 - Find extrema of newly convoluted image */
			IJ.log("Finding extrema...");
			IJ.showStatus("Finding extrema...");
			final RegionalExtremaFactory<T> extremaFactory = new RegionalExtremaFactory<T>(modImg);
			final RegionalExtremaFinder<T> findExtrema = extremaFactory.createRegionalMaximaFinder(true);
			findExtrema.allowEdgeExtrema(allowEdgeMax);
			if (!findExtrema.checkInput() || !findExtrema.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
				System.out.println( "Extrema Finder failed: " + findExtrema.getErrorMessage() );
				return null;
			}
			final ArrayList< double[] > centeredExtrema = findExtrema.getRegionalExtremaCenters(false);
			final ArrayList<Spot> spots = findExtrema.convertToSpots(centeredExtrema);
			downsampledCoordsToOrigCoords(spots, downsampleFactors);
			extremaAllFrames.add(spots);
			System.out.println("Find Maxima Run Time: " + findExtrema.getProcessingTime());
			System.out.println("Num regional maxima: " + centeredExtrema.size());
			
			/* 8 - Assess quality of extrema */
			final AverageScoreAggregator scoreAgg = new AverageScoreAggregator();
			final LoGScorer<T> logScore = new LoGScorer<T>(modImg, downsampleFactors);
			final BlobVarianceScorer<T> varScore = new BlobVarianceScorer<T>(img, diam, calibration);
			final BlobBrightnessScorer<T> brightnessScore = new BlobBrightnessScorer<T>(img, diam, calibration);
			final BlobContrastScorer<T> contrastScore = new BlobContrastScorer<T>(img, diam, calibration);
			final OverlapScorer<T> overlapScore = new OverlapScorer<T>(diam, calibration, spots);
			scoreAgg.add(logScore);
			scoreAgg.add(varScore);
			//scoreAgg.add(brightnessScore);
		    //scoreAgg.add(contrastScore);
		    //scoreAgg.add(overlapScore);
			scoreAgg.aggregate(spots);  // aggregate scores
			
			/* 9 - Calculate Thresholds */
			final double threshold = otsuThreshold(spots);  // determines best cutoff point between "good" and "bad" extrema.
			frameThresholds.add(threshold);
		}
		
		return new Object[] {extremaAllFrames, frameThresholds};
	}
	
	// Code source: http://www.labbookpages.co.uk/software/imgProc/otsuThreshold.html
	public double otsuThreshold(ArrayList<Spot> srcData)
	{
		// Prepare histogram
		int histData[] = histogram(srcData);
		int count = srcData.size();

		// Thresholding
		float sum = 0;
		for (int t=0 ; t<histData.length ; t++) sum += t * histData[t];

		float sumB = 0;
		int wB = 0;
		int wF = 0;

		float varMax = 0;
		int threshold = 0;
		
		for (int t=0 ; t<histData.length ; t++)
		{
			wB += histData[t];					// Weight Background
			if (wB == 0) continue;

			wF = count - wB;					// Weight Foreground
			if (wF == 0) break;

			sumB += (float) (t * histData[t]);

			float mB = sumB / wB;				// Mean Background
			float mF = (sum - sumB) / wF;		// Mean Foreground

			// Calculate Between Class Variance
			float varBetween = (float)wB * (float)wF * (mB - mF) * (mB - mF);	

			// Check if new maximum found
			if (varBetween > varMax) {
				varMax = varBetween;
				threshold = t;
			}
		}
		
		return (threshold + 1) * getRange(srcData)[0] / (double) histData.length;  // Convert the integer bin threshold to a value
	}
	
	/** Histogram currently generated using nBins = n^(1/2) approach, also used by Excel. */
	public int[] histogram (ArrayList<Spot> data) {
		// Calculate number of bins
		int size = data.size();
		int nBins = (int) Math.ceil(Math.sqrt(size));  // nBins = n^(1/2)

		// Create array for histrogram with nBins
		int[] hist = new int[nBins];
		
		// Get data range
		double[] range = getRange(data);
		
		// Populate the histogram with data
		double binWidth = range[0] / nBins;
		for (int i = 0; i < data.size(); i++) {
			int index = Math.min((int) Math.floor((data.get(i).getAggregatedScore() - range[1]) / binWidth), nBins - 1); // the max value ends up being 1 higher than nBins, so put it in the last bin.
			hist[index]++;
		}
		
		return hist;
	}
	
	/** Returns [range, min, max] */
	public double[] getRange(ArrayList<Spot> data) {
		double min = 0;
		double max = 0;
		
		for (int i = 0; i < data.size(); i++) {
			double value = data.get(i).getAggregatedScore();
			if (i == 0) {
				min = value;
				max = value;
			}
			
			else {
				if (value < min) min = value;
				if (value > max) max = value;
			}
		}
		
		return new double[] {(max-min), min, max};
	}
	
	public double[] createDownsampledDim(double[] calibration, double diam, int numDim) {
		double widthFactor = (diam / calibration[0]) > GOAL_DOWNSAMPLED_BLOB_DIAM ? (diam / calibration[0]) / GOAL_DOWNSAMPLED_BLOB_DIAM : 1;
		double heightFactor = (diam / calibration[1]) > GOAL_DOWNSAMPLED_BLOB_DIAM ? (diam / calibration[1]) / GOAL_DOWNSAMPLED_BLOB_DIAM : 1;
		double depthFactor = (numDim == 3 && (diam / calibration[2]) > GOAL_DOWNSAMPLED_BLOB_DIAM) ? (diam / calibration[2]) / GOAL_DOWNSAMPLED_BLOB_DIAM : 1;								
		double downsampleFactors[] = new double[]{widthFactor, heightFactor, depthFactor};
		
		return downsampleFactors;
	}
	
	protected static void quickKernel3D(float[][][] vals, Image<FloatType> kern)
	{
		final LocalizableByDimCursor<FloatType> cursor = kern.createLocalizableByDimCursor();
		final int[] pos = new int[3];

		for (int i = 0; i < vals.length; ++i)
		{
			for (int j = 0; j < vals[i].length; ++j)
			{
				for (int k = 0; k < vals[j].length; ++k)
				{
					pos[0] = i;
					pos[1] = j;
					pos[2] = k;
					cursor.setPosition(pos);
					cursor.getType().set(vals[i][j][k]);
				}
			}
		}
		cursor.close();		
	}
	
	/**
	 * Code courtesy of Larry Lindsey. However, it is protected in the DirectConvolution class,
	 * so I reproduced it here to avoid instantiating an object.
	 * 
	 * @param vals
	 * @param kern
	 */
	protected static void quickKernel2D(float[][] vals, Image<FloatType> kern)
	{
		final LocalizableByDimCursor<FloatType> cursor = kern.createLocalizableByDimCursor();
		final int[] pos = new int[2];

		for (int i = 0; i < vals.length; ++i)
		{
			for (int j = 0; j < vals[i].length; ++j)
			{
				pos[0] = i;
				pos[1] = j;
				cursor.setPosition(pos);
				cursor.getType().set(vals[i][j]);
			}
		}
		cursor.close();		
	}
	
	public PointRoi preparePointRoi (ArrayList< ArrayList< double[] > > extrema, float downsampleFactors[], float pixelWidth, float pixelHeight) {
		int numPoints = extrema.size();
		int ox[] = new int[numPoints];
		int oy[] = new int[numPoints];
		ListIterator< ArrayList<double[]> > framesItr = extrema.listIterator();
		
		while (framesItr.hasNext()) {
			ArrayList<double[]> frame= framesItr.next();
			ListIterator< double[] > itr = frame.listIterator();
			while (itr.hasNext()) {
				int index = 0;
				double curr[] = itr.next();
				ox[index] = (int) (curr[0] * downsampleFactors[0]);
				oy[index] = (int) (curr[1] * downsampleFactors[1]);
				index++;
			}
		}
		PointRoi roi = new PointRoi(ox, oy, numPoints);
		return roi;
	}

	public void renderIn3DViewer(ArrayList< ArrayList<Spot> > extremaAllFrames, ArrayList<Double> thresholdsAllFrames, ImagePlus imp, double[] calibration, double diam) {
		/* 1 - Render ImagePlus using 3D Viewer */
		
		// Convert to a usable format
		new StackConverter(imp).convertToGray8();

		// Create a universe
		Image3DUniverse univ = new Image3DUniverse();
		univ.show();
		
		// Add the image as a volume rendering
		Content c = univ.addVoltex(imp);

		// For each frame
		for (int j = 0; j < extremaAllFrames.size(); j++) {
			PointList pl = c.getInstant(j).getPointList();
			ArrayList<Spot> framej = extremaAllFrames.get(j);
			final double threshold = thresholdsAllFrames.get(j);  // threshold for frame
			Iterator<Spot> itr = framej.iterator();
			
			// Add the extrema coords to the pointlist
			while (itr.hasNext()) {
				final Spot spot = itr.next();
				final double coords[] = spot.getCoordinates();
				
				if (spot.getAggregatedScore() > threshold) {  // if above the threshold
					// Add point
					pl.add(coords[0] * calibration[0], coords[1] * calibration[1], coords[2] * calibration[2]);  // Scale for each dimension, since the coordinates are unscaled now and from the downsampled image.	
					//System.out.println("Point [" + coords[0] * pixelWidth + ", " + coords[1] * pixelHeight + ", " + coords[2] * pixelDepth + "] has score: " + spot.getAggregatedScore());
				}
			}
		}
		
		// Make the point list visible
		c.showPointList(true);
		
		// Change the size of the points
		c.setLandmarkPointSize((float) diam / 2);  // radius is diam / 2
		
		/* 2 - Allow the user to interact with the rendering */
		//univ.setInteractiveBehavior(new ThresholdAdjuster(univ, c));
		//univ.getExecuter().changeThreshold(univ.getSelected());
		univ.getExecuter().changeThreshold(c);
		
		/*final ContentInstant ci = c.getCurrent();
		final SliderAdjuster thresh_adjuster = new SliderAdjuster() {
			public synchronized final void setValue(ContentInstant ci, int v) {
				ci.setThreshold(v);
				univ.fireContentChanged(c);
			}
		};*/
		
	}
	
	private void downsampledCoordsToOrigCoords(ArrayList<Spot> spots, double downsampleFactors[]) {
		Iterator<Spot> itr = spots.iterator();
		while (itr.hasNext()) {
			Spot spot = itr.next();
			double[] coords = spot.getCoordinates();
			
			// Undo downsampling
			for (int i = 0; i < coords.length; i++) {
				coords[i] = coords[i] * downsampleFactors[i];
			}
		}
	}
}
