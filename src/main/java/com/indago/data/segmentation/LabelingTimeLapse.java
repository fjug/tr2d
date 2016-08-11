/**
 *
 */
package com.indago.data.segmentation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.indago.data.segmentation.filteredcomponents.FilteredComponentTree;
import com.indago.data.segmentation.filteredcomponents.FilteredComponentTree.Filter;
import com.indago.data.segmentation.filteredcomponents.FilteredComponentTree.MaxGrowthPerStep;
import com.indago.io.ProjectFile;
import com.indago.io.ProjectFolder;
import com.indago.tr2d.ui.model.Tr2dSegmentationCollectionModel;

import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import weka.gui.ExtensionFileFilter;

/**
 * @author jug
 */
public class LabelingTimeLapse {

	private final Tr2dSegmentationCollectionModel model;

	// Parameters for FilteredComponentTrees
	private final int minComponentSize;
	private final int maxComponentSize;
	private final Filter maxGrowthPerStep;
	private final boolean darkToBright = false;

	private List< LabelingBuilder > frameLabelingBuilders;
	private final Map< LabelingBuilder, ConflictGraph > mapToConflictGraphs = new HashMap< >();

	private boolean processedOrLoaded;

	/**
	 * @param tr2dSegModel2
	 */
	public LabelingTimeLapse( final Tr2dSegmentationCollectionModel model ) {
		this.model = model;

		minComponentSize = 25;
		maxComponentSize = ( int ) ( model.getModel().getRawData().dimension( 0 ) * model.getModel().getRawData().dimension( 1 ) - 1 );
		maxGrowthPerStep = new MaxGrowthPerStep( maxComponentSize );

		frameLabelingBuilders = new ArrayList<>();
		processedOrLoaded = false;
	}

	/**
	 * @return <code>true</code>, if any sum images for processing where found,
	 *         <code>false</code> otherwise
	 */
	public boolean processFrames() {
		try {
			if ( getSegmentHypothesesImages().size() == 0 ) { return false; }

			frameLabelingBuilders = new ArrayList<>();
			final RandomAccessibleInterval< IntType > firstSumImg = getSegmentHypothesesImages().get( 0 );

			for ( int frameId = 0; frameId < firstSumImg.dimension( 2 ); frameId++ ) {

				final Dimensions d = new FinalDimensions( firstSumImg.dimension( 0 ), firstSumImg.dimension( 1 ) );
				final LabelingBuilder labelingBuilder = new LabelingBuilder( d );
				frameLabelingBuilders.add( labelingBuilder );

				for ( final RandomAccessibleInterval< IntType > sumimg : getSegmentHypothesesImages() ) {
					// hyperslize desired frame
					IntervalView< IntType > frame = null;
					final long[] offset = new long[ sumimg.numDimensions() ];
					offset[ offset.length - 1 ] = frameId;
					frame = Views.offset(
							Views.hyperSlice( sumimg, 2, frameId ),
							offset );
					// build component tree on frame
					final FilteredComponentTree< IntType > tree =
							FilteredComponentTree.buildComponentTree(
									frame,
									new IntType(),
									minComponentSize,
									maxComponentSize,
									maxGrowthPerStep,
									darkToBright );
					labelingBuilder.buildLabelingForest( tree );
				}
			}
			processedOrLoaded = true;
		} catch ( final IllegalAccessException e ) {
			// This happens if getSegmentHypothesesImages() is called but none are there yet...
			processedOrLoaded = false;
		}
		return processedOrLoaded;
	}

	/**
	 * @return
	 * @throws IllegalAccessException
	 */
	public List< RandomAccessibleInterval< IntType > > getSegmentHypothesesImages()
			throws IllegalAccessException {
		return model.getSumImages();
	}

	/**
	 * @return
	 */
	public int getNumFrames() {
		return frameLabelingBuilders.size();
	}

	/**
	 * @param frameId
	 * @return
	 */
	public List< LabelingSegment > getLabelingSegmentsForFrame( final int frameId ) {
		return frameLabelingBuilders.get( frameId ).getSegments();
	}

	/**
	 * Returns the <code>LabelingPlus</code> for the requested frame.
	 *
	 * @param frameId
	 * @return the <code>LabelingPlus</code> requested, or <code>null</code> if
	 *         it does not exists.
	 */
	public LabelingPlus getLabelingPlusForFrame( final int frameId ) {
		if ( frameId < frameLabelingBuilders.size() )
			return frameLabelingBuilders.get( frameId );
		else
			return null;
	}

	/**
	 * @param frameId
	 * @return
	 */
	public ConflictGraph< LabelingSegment > getConflictGraph( final int frameId ) {
		final LabelingBuilder key = frameLabelingBuilders.get( frameId );
		if ( !mapToConflictGraphs.containsKey( key ) ) {
			mapToConflictGraphs.put( key, new MinimalOverlapConflictGraph( frameLabelingBuilders.get( frameId ) ) );
		}
		return mapToConflictGraphs.get( key );
	}

	/**
	 *
	 */
	public void loadFromProjectFolder( final ProjectFolder folder ) {
		frameLabelingBuilders.clear();
		processedOrLoaded = false;
		for ( final ProjectFile labelingFrameFile : folder.getFiles( new ExtensionFileFilter( "xml", "XML files" ) ) ) {
			final File fLabeling = labelingFrameFile.getFile();
			if ( fLabeling.canRead() ) {
				try {
					final LabelingPlus labelingPlus = new XmlIoLabelingPlus().load( fLabeling );
					frameLabelingBuilders.add( new LabelingBuilder( labelingPlus ) );
				} catch ( final IOException e ) {
					System.err.println( String.format( "ERROR: Labeling could not be loaded! (%s)", fLabeling.toString() ) );
//					e.printStackTrace();
				}
			}
			processedOrLoaded = true;
		}
	}

	/**
	 * @return
	 */
	public boolean needProcessing() {
		return !processedOrLoaded;
	}

	/**
	 * @param folder
	 */
	public void saveTo( final ProjectFolder folder ) {
		final String fnPrefix = "labeling_frame";
		int i = 0;
		for ( final LabelingBuilder lb : frameLabelingBuilders ) {
			final String fn = String.format( "%s%04d.xml", fnPrefix, i );
			final String abspath = new File( folder.getFolder(), fn ).getAbsolutePath();
			try {
				new XmlIoLabelingPlus().save( lb, abspath );
			} catch ( final IOException e ) {
				System.err.println( String.format( "ERROR: could not store labeling_frame%04d.* to project folder!", i ) );
//				e.printStackTrace();
			}
			i++;
		}
	}

}
