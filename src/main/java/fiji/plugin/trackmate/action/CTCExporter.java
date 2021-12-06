package fiji.plugin.trackmate.action;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.traverse.BreadthFirstIterator;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.action.LabelImgExporter.SpotRoiWriter;
import fiji.plugin.trackmate.graph.ConvexBranchesDecomposition;
import fiji.plugin.trackmate.graph.ConvexBranchesDecomposition.TrackBranchDecomposition;
import fiji.plugin.trackmate.graph.GraphUtils;
import fiji.plugin.trackmate.graph.TimeDirectedNeighborIndex;
import fiji.plugin.trackmate.util.TMUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.Duplicator;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Util;

/**
 * An exporter that saves the current TrackMate session as files following the
 * Cell-Tracking-Challenge convention.
 * <p>
 * See http://celltrackingchallenge.net/
 * 
 * @author Jean-Yves Tinevez
 *
 */
public class CTCExporter
{

	private static final String GOLD_TRUTH_SUFFIX = "_GT";

	private static final String SILVER_TRUTH_SUFFIX = "_ST";

	private static final String RESUTS_SUFFIX = "_RES";

	private static final Function< Integer, String > nameGen = i -> String.format( "%02d", i );

	public static void exportAll( final String exportRootFolder, final TrackMate trackmate, final String suffix ) throws IOException
	{
		final int id = getAvailableDatasetID( exportRootFolder );
		exportOriginalImageData( exportRootFolder, id, trackmate );
		exportTrackingData( exportRootFolder, id, suffix, trackmate );
		exportSegmentationData( exportRootFolder, id, suffix, trackmate );
	}

	/**
	 * Returns a suitable dataset id to save in the specified folder.
	 * <p>
	 * For instance the first id return will be '1', which means that the
	 * original image data will be saved under the folder '01'. If '01' already
	 * exists, then this method will return 2, etc.
	 * 
	 * @param exportRootFolder
	 *            the root folder in which to export the data.
	 * @return an integer id that can be passed in the other method of this
	 *         class.
	 */
	public static int getAvailableDatasetID( final String exportRootFolder )
	{
		int i = 1;
		Path savePath1 = Paths.get( exportRootFolder, nameGen.apply( i ) );
		Path savePath2 = Paths.get( exportRootFolder, nameGen.apply( i ) + GOLD_TRUTH_SUFFIX );
		Path savePath3 = Paths.get( exportRootFolder, nameGen.apply( i ) + SILVER_TRUTH_SUFFIX );
		Path savePath4 = Paths.get( exportRootFolder, nameGen.apply( i ) + RESUTS_SUFFIX );
		while ( Files.exists( savePath1 ) || Files.exists( savePath2 ) || Files.exists( savePath3 ) || Files.exists( savePath4 ) )
		{
			i++;
			savePath1 = Paths.get( exportRootFolder, nameGen.apply( i ) );
			savePath2 = Paths.get( exportRootFolder, nameGen.apply( i ) + GOLD_TRUTH_SUFFIX );
			savePath3 = Paths.get( exportRootFolder, nameGen.apply( i ) + SILVER_TRUTH_SUFFIX );
			savePath4 = Paths.get( exportRootFolder, nameGen.apply( i ) + RESUTS_SUFFIX );
		}
		return i;
	}

	public static void exportOriginalImageData( final String exportRootFolder, final int saveId, final TrackMate trackmate ) throws IOException
	{
		exportOriginalImageData( exportRootFolder, saveId, trackmate.getSettings().imp );
	}

	public static void exportOriginalImageData( final String exportRootFolder, final int saveId, final ImagePlus imp ) throws IOException
	{
		if ( imp == null )
			return;

		final Path savePath = Paths.get( exportRootFolder, String.format( "%02d", saveId ) );
		if ( Files.exists( savePath ) )
			throw new IOException( "Cannot save to " + savePath + ". Folder already exists." );

		Files.createDirectory( savePath );

		final int nFrames = imp.getNFrames();
		final String format = ( nFrames > 999 ) ? "t%04d.tif" : "t%03d.tif";
		final Duplicator duplicator = new Duplicator();
		final int firstC = 1;
		final int lastC = imp.getNChannels();
		final int firstZ = 1;
		final int lastZ = imp.getNSlices();

		for ( int frame = 0; frame < nFrames; frame++ )
		{
			final ImagePlus tp = duplicator.run( imp, firstC, lastC, firstZ, lastZ, frame + 1, frame + 1 );
			IJ.saveAsTiff( tp, Paths.get( savePath.toString(), String.format( format, frame ) ).toString() );
		}
	}

	public static void exportSegmentationData( final String exportRootFolder, final int saveId, final String suffix, final TrackMate trackmate ) throws IOException
	{
		// Create image holder to write labels in.
		final ImagePlus imp = trackmate.getSettings().imp;
		final long[] dims;
		if ( imp != null )
		{
			final int[] dimensions = imp.getDimensions();
			dims = new long[] { dimensions[ 0 ], dimensions[ 1 ], dimensions[ 3 ], dimensions[ 4 ] };
		}
		else
		{
			final Settings s = trackmate.getSettings();
			dims = new long[] { s.width, s.height, s.nslices, s.nframes };
		}
		final double[] calibration = new double[] {
				imp.getCalibration().pixelWidth,
				imp.getCalibration().pixelHeight,
				imp.getCalibration().pixelDepth,
				imp.getCalibration().frameInterval
		};
		final ImgPlus< UnsignedShortType > labelImg = createLabelImg( dims, calibration );

		// Write labels in.
		final Model model = trackmate.getModel();
		final AtomicInteger idGen = new AtomicInteger( 0 );
		for ( int frame = 0; frame < dims[ 3 ]; frame++ )
		{
			final ImgPlus< UnsignedShortType > imgCT = TMUtils.hyperSlice( labelImg, 0, frame );
			final SpotRoiWriter spotWriter = new SpotRoiWriter( imgCT );

			for ( final Spot spot : model.getSpots().iterable( frame, true ) )
			{
				final int id = idGen.getAndIncrement();
				spotWriter.write( spot, id );
			}
		}

		/*
		 * Now export the label image.
		 */

		final Path path = Paths.get( exportRootFolder, nameGen.apply( saveId ) + suffix, "SEG" );
		Files.createDirectories( path );
		final int nFrames = imp.getNFrames();
		final Function< Long, String > tifNameGen = nFrames > 999
				? i -> String.format( "man_seg%04d.tif", i )
				: i -> String.format( "man_seg%03d.tif", i );
				
		// Only save frames with spots in.
		for ( final int frame : model.getSpots().keySet() )
		{
			final ImgPlus< UnsignedShortType > imgCT = TMUtils.hyperSlice( labelImg, 0, frame );
			final String name = tifNameGen.apply( ( long ) frame );
			final ImagePlus tp = ImageJFunctions.wrapUnsignedShort( imgCT, name );

			final Path pathTif = Paths.get( exportRootFolder, nameGen.apply( saveId ) + suffix, "SEG", name );
			IJ.saveAsTiff( tp, pathTif.toString() );
		}
	}

	public static void exportTrackingData( final String exportRootFolder, final int saveId, final String suffix, final TrackMate trackmate ) throws FileNotFoundException, IOException
	{
		// What we need to decompose tracks in branches.
		final Model model = trackmate.getModel();
		final TrackModel trackModel = model.getTrackModel();
		final TimeDirectedNeighborIndex neighborIndex = model.getTrackModel().getDirectedNeighborIndex();

		// Sanity check.
		if ( !GraphUtils.isTree( trackModel, neighborIndex ) )
			throw new IllegalArgumentException( "Cannot perform CTC export of tracks that have fusion events." );

		// Create image holder to write labels in.
		final ImagePlus imp = trackmate.getSettings().imp;
		final long[] dims;
		if ( imp != null )
		{
			final int[] dimensions = imp.getDimensions();
			dims = new long[] { dimensions[ 0 ], dimensions[ 1 ], dimensions[ 3 ], dimensions[ 4 ] };
		}
		else
		{
			final Settings s = trackmate.getSettings();
			dims = new long[] { s.width, s.height, s.nslices, s.nframes };
		}
		final double[] calibration = new double[] {
				imp.getCalibration().pixelWidth,
				imp.getCalibration().pixelHeight,
				imp.getCalibration().pixelDepth,
				imp.getCalibration().frameInterval
		};
		final ImgPlus< UnsignedShortType > labelImg = createLabelImg( dims, calibration );

		// Configure the convex branch decomposition.
		final boolean forbidMiddleLinks = true;
		final boolean forbidGaps = false;
		final AtomicInteger branchIDGen = new AtomicInteger( 0 );

		// Map of vertex to their ID in the file. Initially empty.
		final Map< List< Spot >, Integer > branchID = new HashMap<>();

		final Path path = Paths.get( exportRootFolder, nameGen.apply( saveId ) + suffix, "TRA", "man_track.txt" );
		Files.createDirectories( path.getParent() );
		try (FileOutputStream fos = new FileOutputStream( path.toFile() );
				BufferedWriter bw = new BufferedWriter( new OutputStreamWriter( fos ) ))
		{

			for ( final Integer trackID : trackModel.trackIDs( true ) )
			{
				// Decompose the track.
				final TrackBranchDecomposition decomposition = ConvexBranchesDecomposition.processTrack( trackID, trackModel, neighborIndex, forbidMiddleLinks, forbidGaps );
				final SimpleDirectedGraph< List< Spot >, DefaultEdge > branchGraph = ConvexBranchesDecomposition.buildBranchGraph( decomposition );

				// Find the first branch (the one with no parent).
				List< Spot > start = null;
				for ( final List< Spot > vertex : branchGraph.vertexSet() )
				{
					if ( branchGraph.incomingEdgesOf( vertex ).isEmpty() )
					{
						start = vertex;
						break;
					}
				}

				// Iterate from the start.
				final BreadthFirstIterator< List< Spot >, DefaultEdge > bfi = new BreadthFirstIterator<>( branchGraph, start );
				while ( bfi.hasNext() )
				{
					final List< Spot > current = bfi.next();
					final int currentID = branchIDGen.getAndIncrement();
					branchID.put( current, Integer.valueOf( currentID ) );

					/*
					 * Write spot label into output image.
					 */

					for ( final Spot spot : current )
					{
						final long frame = spot.getFeature( Spot.FRAME ).longValue();
						final ImgPlus< UnsignedShortType > imgCT = TMUtils.hyperSlice( labelImg, 0, frame );
						final SpotRoiWriter spotRoiWriter = new SpotRoiWriter( imgCT );
						spotRoiWriter.write( spot, currentID );
					}

					/*
					 * Text file.
					 */

					// Get parent ID, if any.
					int parentID;
					if ( branchGraph.incomingEdgesOf( current ).isEmpty() )
					{
						parentID = 0;
					}
					else
					{
						final DefaultEdge edge = branchGraph.incomingEdgesOf( current ).iterator().next();
						final List< Spot > parent = Graphs.getOppositeVertex( branchGraph, edge, current );
						parentID = branchID.get( parent );
					}

					// Start and finish frame.
					final int startFrame = current.get( 0 ).getFeature( Spot.FRAME ).intValue();
					final int endFrame = current.get( current.size() - 1 ).getFeature( Spot.FRAME ).intValue();

					// Write it down.
					final int L = currentID; // track label
					final int B = startFrame; // track begins
					final int E = endFrame; // track ends
					final int P = parentID; // parent ID
					bw.write( String.format( "%d %d %d %d", L, B, E, P ) );
					bw.newLine();
				}
			}
		}

		/*
		 * Now export the label image.
		 */

		final int nFrames = imp.getNFrames();
		final Function< Long, String > tifNameGen = nFrames > 999
				? i -> String.format( "man_track%04d.tif", i )
				: i -> String.format( "man_track%03d.tif", i );
		for ( long frame = 0; frame < dims[ 3 ]; frame++ )
		{
			final ImgPlus< UnsignedShortType > imgCT = TMUtils.hyperSlice( labelImg, 0, frame );
			final String name = tifNameGen.apply( frame );
			final ImagePlus tp = ImageJFunctions.wrapUnsignedShort( imgCT, name );

			final Path pathTif = Paths.get( exportRootFolder, nameGen.apply( saveId ) + suffix, "TRA", name );
			IJ.saveAsTiff( tp, pathTif.toString() );
		}
	}

	/**
	 * Creates a new label {@link ImgPlus} suitable to be used to write spot
	 * labels in. It is initially empty.
	 *
	 * @param dimensions
	 *            the desired dimensions of the output image (width, height,
	 *            nZSlices, nFrames) as a 4 element int array. Spots outside
	 *            these dimensions are ignored.
	 *
	 * @return a new {@link ImgPlus}.
	 */
	private static final ImgPlus< UnsignedShortType > createLabelImg(
			final long[] dimensions,
			final double[] calibration )
	{
		final Dimensions targetSize = FinalDimensions.wrap( dimensions );
		final Img< UnsignedShortType > lblImg = Util.getArrayOrCellImgFactory( targetSize, new UnsignedShortType() ).create( targetSize );
		final AxisType[] axes = new AxisType[] {
				Axes.X,
				Axes.Y,
				Axes.Z,
				Axes.TIME };
		final ImgPlus< UnsignedShortType > imgPlus = new ImgPlus<>( lblImg, "LblImg", axes, calibration );
		return imgPlus;
	}
}