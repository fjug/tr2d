/**
 *
 */
package com.jug.tr2d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.indago.fg.Assignment;
import com.indago.fg.CostsFactory;
import com.indago.fg.FactorGraph;
import com.indago.fg.domain.BooleanDomain;
import com.indago.fg.factor.BooleanFactor;
import com.indago.fg.factor.Factor;
import com.indago.fg.function.BooleanConflictConstraint;
import com.indago.fg.function.BooleanFunction;
import com.indago.fg.function.BooleanTensorTable;
import com.indago.fg.function.BooleanWeightedIndexSumConstraint;
import com.indago.fg.value.BooleanValue;
import com.indago.fg.variable.BooleanVariable;
import com.indago.fg.variable.Variable;
import com.indago.segment.LabelingBuilder;
import com.indago.segment.LabelingForest;
import com.indago.segment.LabelingSegment;
import com.indago.segment.MinimalOverlapConflictGraph;
import com.indago.segment.Segment;
import com.indago.segment.fg.FactorGraphFactory;
import com.indago.segment.fg.FactorGraphPlus;
import com.indago.segment.fg.SegmentHypothesisVariable;
import com.indago.segment.filteredcomponents.FilteredComponentTree;
import com.indago.segment.filteredcomponents.FilteredComponentTree.Filter;
import com.indago.segment.filteredcomponents.FilteredComponentTree.MaxGrowthPerStep;
import com.jug.tr2d.datasets.hernan.HernanAppearanceCostFactory;
import com.jug.tr2d.datasets.hernan.HernanCostConstants;
import com.jug.tr2d.datasets.hernan.HernanDisappearanceCostFactory;
import com.jug.tr2d.datasets.hernan.HernanDivisionCostFactory;
import com.jug.tr2d.datasets.hernan.HernanMappingCostFactory;
import com.jug.tr2d.datasets.hernan.HernanSegmentCostFactory;
import com.jug.tr2d.fg.Tr2dFactorGraphFactory;
import com.jug.tr2d.fg.Tr2dFactorGraphPlus;
import com.jug.tr2d.fg.factor.DivisionFactor;
import com.jug.tr2d.fg.factor.MappingFactor;
import com.jug.tr2d.fg.variables.AppearanceHypothesisVariable;
import com.jug.tr2d.fg.variables.DisappearanceHypothesisVariable;
import com.jug.tr2d.fg.variables.DivisionHypothesisVariable;
import com.jug.tr2d.fg.variables.MappingHypothesisVariable;
import com.jug.util.DataMover;

import gurobi.GRB;
import gurobi.GRB.DoubleAttr;
import gurobi.GRBEnv;
import gurobi.GRBException;
import gurobi.GRBLinExpr;
import gurobi.GRBModel;
import gurobi.GRBVar;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * @author jug
 */
public class Tr2dTrackingModel {

	public static class GurobiReadouts {

		public int numIterations;
		public double runtime;
		public double objval;

		public GurobiReadouts(
				final int numIterations,
				final double runtime,
				final double objval ) {
			this.numIterations = numIterations;
			this.runtime = runtime;
			this.objval = objval;
		}
	}

	private static Assignment assignment;

	private final Tr2dModel tr2dModel;
	private final Tr2dWekaSegmentationModel tr2dSegModel;

	// Parameters for FilteredComponentTree of SumImage(s)
	private Dimensions dim;
	private final int minComponentSize = 10;
	private final int maxComponentSize = 10000;
	private final Filter maxGrowthPerStep;
	private final boolean darkToBright = false;

	// factor graph (plus association data structures)
	private Tr2dFactorGraphPlus fgPlus = new Tr2dFactorGraphPlus();

	private RandomAccessibleInterval< DoubleType > imgSolution = null;


	/**
	 * @param model
	 */
	public Tr2dTrackingModel( final Tr2dModel model, final Tr2dWekaSegmentationModel modelSeg ) {
		this.tr2dModel = model;
		this.tr2dSegModel = modelSeg;

		maxGrowthPerStep = new MaxGrowthPerStep( 1 );
	}

	/**
	 * @return
	 * @throws IllegalAccessException
	 */
	public RandomAccessibleInterval< DoubleType > getSegmentHypothesesImage()
			throws IllegalAccessException {
		return tr2dSegModel.getSegmentHypotheses();
	}

	/**
	 * This method creates the tracking FG for the entire given time-series.
	 */
	@SuppressWarnings( "unchecked" )
	public void run() {
		fgPlus = new Tr2dFactorGraphPlus();

		long t0, t1;
		List< LabelingSegment > segments;
		Collection< SegmentHypothesisVariable< Segment > > segVars;
		List< LabelingSegment > oldSegments = null;
		Collection< SegmentHypothesisVariable< Segment > > oldSegVars = null;

		// set dim
		try {
			dim = getSegmentHypothesesImage();
			System.out.println( "Input image dimensions: " + dim.toString() );
		} catch ( final IllegalAccessException e ) {
			System.err.println( "Segmentation Hypotheses could not be accessed!" );
			e.printStackTrace();
			return;
		}

		// ===============================================================================================

		// go over frames and create and add frameFGs
		// + assignmentFGs between adjacent frames
		// ---------------------------------------------------------
		final long numFrames = dim.dimension( 2 );
		for ( long frameId = 0; frameId < numFrames; frameId++ ) {
			System.out.println(
					String.format( "Working on frame %d of %d...", frameId + 1, numFrames ) );

			final List< LabelingForest > frameLabelingForests = new ArrayList< LabelingForest >();

			// Hyperslize current frame out of complete dataset
			IntervalView< DoubleType > hsFrame = null;
			try {
				final long[] offset = new long[ getSegmentHypothesesImage().numDimensions() ];
				offset[ offset.length - 1 ] = frameId;
				hsFrame = Views.offset(
						Views.hyperSlice( getSegmentHypothesesImage(), 2, frameId ),
						offset );
//				ImageJFunctions.show( hsFrame );
			} catch ( final IllegalAccessException e ) {
				System.err.println( "\tSegmentation Hypotheses could not be accessed!" );
				e.printStackTrace();
				return;
			}

			System.out.print( "\tBuilding FilteredComponentTree and LabelingForest... " );
			t0 = System.currentTimeMillis();
			final FilteredComponentTree< DoubleType > tree =
					FilteredComponentTree.buildComponentTree(
							hsFrame,
							new DoubleType(),
							minComponentSize,
							maxComponentSize,
							maxGrowthPerStep,
							darkToBright );
			final LabelingBuilder labelingBuilder = new LabelingBuilder( dim );
			frameLabelingForests.add( labelingBuilder.buildLabelingForest( tree ) );
			t1 = System.currentTimeMillis();
			System.out
					.println(
							String.format(
									"\n\t...completed in %.2f seconds!",
									( t1 - t0 ) / 1000. ) );

			System.out.print( "\tConstructing MinimalOverlapConflictGraph... " );
			t0 = System.currentTimeMillis();
			final MinimalOverlapConflictGraph conflictGraph =
					new MinimalOverlapConflictGraph( labelingBuilder );
			conflictGraph.getConflictGraphCliques();
			t1 = System.currentTimeMillis();
			System.out
					.println(
							String.format(
									"\n\t...completed in %.2f seconds!",
									( t1 - t0 ) / 1000. ) );

			// =============
			// FRAME FG
			// =============
			System.out.print( "\tConstructing frameFG from MinimalOverlapConflictGraph... " );
			t0 = System.currentTimeMillis();
			segments = labelingBuilder.getSegments();
			final CostsFactory< Segment > segmentCosts =
					new HernanSegmentCostFactory( frameId, tr2dModel.getImgOrig() );
			final FactorGraphPlus frameFG = FactorGraphFactory
					.createFromConflictGraph( segments, conflictGraph, segmentCosts );
			segVars = ( Collection< SegmentHypothesisVariable< Segment > > ) frameFG
					.getFactorGraph()
					.getVariables();
			t1 = System.currentTimeMillis();
			System.out
					.println(
							String.format(
									"\n\t...completed in %.2f seconds!",
									( t1 - t0 ) / 1000. ) );

			// =============
			// TRANSITION FG
			// =============
			FactorGraphPlus< Segment > transFG = null;
			if ( frameId > 0 ) {
				System.out.print( "\tConstructing transFG... " );
				t0 = System.currentTimeMillis();
				final CostsFactory< Pair< Segment, Segment > > mappingCosts =
						new HernanMappingCostFactory( frameId, tr2dModel.getImgOrig() );
				final CostsFactory< Pair< Segment, Pair< Segment, Segment > > > divisionCosts =
						new HernanDivisionCostFactory( frameId, tr2dModel.getImgOrig() );
				final CostsFactory< Segment > appearanceCosts =
						new HernanAppearanceCostFactory( frameId, tr2dModel.getImgOrig() );
				final CostsFactory< Segment > disappearanceCosts =
						new HernanDisappearanceCostFactory( frameId, tr2dModel.getImgOrig() );
				transFG = new Tr2dFactorGraphFactory()
						.createTransitionGraph(
								fgPlus,
								oldSegVars,
								segVars,
								mappingCosts,
								HernanCostConstants.TRUNCATE_COST_THRESHOLD,
								divisionCosts,
								HernanCostConstants.TRUNCATE_COST_THRESHOLD,
								appearanceCosts,
								HernanCostConstants.TRUNCATE_COST_THRESHOLD,
								disappearanceCosts,
								HernanCostConstants.TRUNCATE_COST_THRESHOLD );
				t1 = System.currentTimeMillis();
				System.out.println(
						String.format( "\n\t...completed in %.2f seconds!", ( t1 - t0 ) / 1000. ) );
			}

			// =============
			//   ADD FRAME
			// =============
			System.out.print( "\tAdding new FGs... " );
			t0 = System.currentTimeMillis();
			if ( frameId == 0 ) {
				fgPlus.addFirstFrame( frameFG );
			} else {
				fgPlus.addFrame( transFG, frameFG );
			}
			t1 = System.currentTimeMillis();
			System.out
					.println(
							String.format(
									"\n\t...completed in %.2f seconds!",
									( t1 - t0 ) / 1000. ) );

			oldSegments = segments;
			oldSegVars = segVars;
		}

		System.out.println( "FG successfully built!\n" );

		// ===============================================================================================

		System.out.println( "Constructing and solving ILP... " );
		t0 = System.currentTimeMillis();
		GurobiReadouts gurobiStats;
		try {
			gurobiStats = buildAndRunILP( fgPlus );
		} catch ( final GRBException e ) {
			e.printStackTrace();
		}
		t1 = System.currentTimeMillis();
		System.out
				.println( String.format( "...completed in %.2f seconds!", ( t1 - t0 ) / 1000. ) );

		// ===============================================================================================

		System.out.println( "Visualize tracking results... " );
		t0 = System.currentTimeMillis();
		try {
			imgSolution =
					DataMover.createEmptyArrayImgLike(
							getSegmentHypothesesImage(),
							new DoubleType() );
			int trackletId = 1;
			final Set< SegmentHypothesisVariable< Segment > > seenSegVars = new HashSet< >();
			for ( long frameId = 0; frameId < numFrames; frameId++ ) {
				final FactorGraphPlus< Segment > firstFrameFG = fgPlus.getFrameFGs().get( 0 );
				for ( final SegmentHypothesisVariable< Segment > segVar : firstFrameFG
						.getSegmentVariables() ) {
					if ( assignment.getAssignment( segVar ).get()
							&& !seenSegVars.contains( segVar ) ) {
						recursivelyPaintTracklet( segVar, seenSegVars, trackletId, frameId );
						trackletId++;
					}

				}
			}
			ImageJFunctions.show( imgSolution );
		} catch ( final IllegalAccessException e ) {
			e.printStackTrace();
		}

		t1 = System.currentTimeMillis();
		System.out
				.println( String.format( "...completed in %.2f seconds!", ( t1 - t0 ) / 1000. ) );

	}

	/**
	 * @param segVar
	 * @param seenSegVars
	 * @param trackletId
	 * @param frameId
	 */
	private void recursivelyPaintTracklet(
			final SegmentHypothesisVariable< Segment > startVar,
			final Set< SegmentHypothesisVariable< Segment > > seenSegVars,
			final int trackletId,
			long frameId ) {

		final LinkedList< Pair< Long, SegmentHypothesisVariable< Segment > > > queue =
				new LinkedList< >();
		queue.add(
				new ValuePair< Long, SegmentHypothesisVariable< Segment > >( frameId, startVar ) );

		while ( !queue.isEmpty() ) {
			final Pair< Long, SegmentHypothesisVariable< Segment > > qelem = queue.remove();
			frameId = qelem.getA();
			final SegmentHypothesisVariable< Segment > segVar = qelem.getB();
			paintSegment( imgSolution, segVar.getSegment(), frameId, trackletId );
			seenSegVars.add( segVar );

			final List< BooleanVariable > rns = segVar.getRightNeighbors();
			for ( final BooleanVariable rn : rns ) {
				for ( final Factor< BooleanDomain, ?, ? > fac : rn.getFactors() ) {
					if ( fac instanceof MappingFactor ) {
						final BooleanVariable var0 = ( BooleanVariable ) fac.getVariable( 0 );
						final SegmentHypothesisVariable< Segment > var2 =
								( SegmentHypothesisVariable< Segment > ) fac.getVariable( 2 );
						if ( assignment.getAssignment( var0 ).get() ) {
							queue.add(
									new ValuePair< Long, SegmentHypothesisVariable< Segment > >( frameId + 1, var2 ) );
						}
					}
					if ( fac instanceof DivisionFactor ) {
						final BooleanVariable var0 = ( BooleanVariable ) fac.getVariable( 0 );
						final SegmentHypothesisVariable< Segment > var2 =
								( SegmentHypothesisVariable< Segment > ) fac.getVariable( 2 );
						final SegmentHypothesisVariable< Segment > var3 =
								( SegmentHypothesisVariable< Segment > ) fac.getVariable( 3 );
						if ( assignment.getAssignment( var0 ).get() ) {
							queue.add(
									new ValuePair< Long, SegmentHypothesisVariable< Segment > >( frameId + 1, var2 ) );
							queue.add(
									new ValuePair< Long, SegmentHypothesisVariable< Segment > >( frameId + 1, var3 ) );
						}
					}
				}
			}
		}
	}

	/**
	 * @param imgSolution2
	 * @param segment
	 */
	private void paintSegment(
			final RandomAccessibleInterval< DoubleType > img,
			final Segment segment,
			final long time,
			final long id ) {
		final Cursor< ? > cSegment = segment.getRegion().cursor();
		final RandomAccess< DoubleType > ra = img.randomAccess();
		while ( cSegment.hasNext() ) {
			cSegment.fwd();
			ra.setPosition( cSegment );
			ra.setPosition( time, 2 );
			ra.get().set( new DoubleType( id ) );
		}
	}

	private static GurobiReadouts buildAndRunILP( final FactorGraph fg ) throws GRBException {
		for ( final Variable< ? > variable : fg.getVariables() ) {
			if ( !( variable instanceof BooleanVariable ) )
				throw new IllegalArgumentException( "Non boolean variable found (and so far not supported)!" );
		}
		final ArrayList< BooleanFactor > constraints = new ArrayList< BooleanFactor >();
		final ArrayList< BooleanFactor > unaries = new ArrayList< BooleanFactor >();
		for ( final Factor< ?, ?, ? > f : fg.getFactors() ) {
			if ( f instanceof BooleanFactor ) {
				final BooleanFactor factor = ( BooleanFactor ) f;
				final BooleanFunction function = factor.getFunction();
				if ( function instanceof BooleanConflictConstraint )
					constraints.add( factor );
				else if ( function instanceof BooleanTensorTable )
					unaries.add( factor );
				else if ( function instanceof BooleanWeightedIndexSumConstraint )
					constraints.add( factor );
				else
					throw new IllegalArgumentException( "Function that fucks it up: " + function
							.getClass()
							.toString() );
			} else
				throw new IllegalArgumentException( "Factor that fucks it up: " + f
						.getClass()
						.toString() );
		}

		final List< BooleanVariable > variables = ( List< BooleanVariable > ) fg.getVariables();
		final HashMap< BooleanVariable, Integer > variableToIndex = new HashMap< >();
		int variableIndex = 0;
		for ( final BooleanVariable v : variables )
			variableToIndex.put( v, variableIndex++ );

		final GRBEnv env = new GRBEnv( "mip1.log" );
		final GRBModel model = new GRBModel( env );

		// Create variables
		System.out.println( String.format( "\tadding %d variables...", variables.size() ) );
		final GRBVar[] vars = model.addVars( variables.size(), GRB.BINARY );

		// Integrate new variables
		model.update();

		// Set objective: minimize costs
		System.out.println( String.format( "\tsetting objective function..." ) );
		final double[] coeffs = new double[ variables.size() ];
		for ( final BooleanFactor factor : unaries ) {
			final int i = variableToIndex.get( factor.getVariable( 0 ) );
			final BooleanTensorTable costs = ( BooleanTensorTable ) factor.getFunction();
			coeffs[ i ] =
					costs.evaluate( BooleanValue.TRUE ) - costs.evaluate( BooleanValue.FALSE );
		}
		final GRBLinExpr expr = new GRBLinExpr();
		expr.addTerms( coeffs, vars );
		model.setObjective( expr, GRB.MINIMIZE );

		// Add constraints.
		System.out.println( String.format( "\tadding %d constraints...", constraints.size() ) );
		for ( int i = 0; i < constraints.size(); i++ ) {
			final BooleanFactor constraint = constraints.get( i );
			final GRBLinExpr lhsExprs = new GRBLinExpr();
			if ( constraint.getFunction() instanceof BooleanWeightedIndexSumConstraint ) {
				final BooleanWeightedIndexSumConstraint fkt =
						( BooleanWeightedIndexSumConstraint ) constraint.getFunction();
				final double[] c = fkt.getCoefficients();
				int ci = 0;
				for ( final BooleanVariable variable : constraint.getVariables() ) {
					try {
						final int vi = variableToIndex.get( variable );
						lhsExprs.addTerm( c[ ci ], vars[ vi ] );
						ci++;
					} catch ( final Exception e ) {
						e.printStackTrace();
					}
				}
				char grb_rel = GRB.EQUAL;
				switch ( fkt.getRelation() ) {
				default:
				case EQ:
					grb_rel = GRB.EQUAL;
					break;
				case GE:
					grb_rel = GRB.GREATER_EQUAL;
					break;
				case LE:
					grb_rel = GRB.LESS_EQUAL;
					break;
				}
				model.addConstr( lhsExprs, grb_rel, fkt.getRHS(), null );
			} else if ( constraint.getFunction() instanceof BooleanConflictConstraint) {
    			for ( final BooleanVariable variable : constraint.getVariables() ) {
    				final int vi = variableToIndex.get( variable );
    				lhsExprs.addTerm( 1.0, vars[ vi ] );
				}
				model.addConstr( lhsExprs, GRB.LESS_EQUAL, 1.0, null );
			}
		}

		// Optimize model
		System.out.println( String.format( "\tstarting optimization..." ) );
		model.optimize();
		final int iterCount = ( int ) Math.round( model.get( GRB.DoubleAttr.IterCount ) );
		final double solvingTime = model.get( GRB.DoubleAttr.Runtime );
		final double objval = model.get( GRB.DoubleAttr.ObjVal );
//		System.out.println( "Obj: " + model.get( GRB.DoubleAttr.ObjVal ) );

		// Build assignment
		System.out.println( String.format( "\tretrieving (optimal) assignment..." ) );
		assignment = new Assignment( variables );
		int mappings, divisions, appearances, disappearances;
		mappings = divisions = appearances = disappearances = 0;
		for ( int i = 0; i < variables.size(); i++ ) {
			final BooleanVariable variable = variables.get( i );
			final BooleanValue value =
					vars[ i ].get( DoubleAttr.X ) > 0.5 ? BooleanValue.TRUE : BooleanValue.FALSE;
			if ( value.equals( BooleanValue.TRUE ) ) {
				if ( variable instanceof MappingHypothesisVariable ) {
					mappings++;
				}
				if ( variable instanceof DivisionHypothesisVariable ) {
					divisions++;
				}
				if ( variable instanceof AppearanceHypothesisVariable ) {
					appearances++;
				}
				if ( variable instanceof DisappearanceHypothesisVariable ) {
					disappearances++;
				}
//				System.out.println( variable + " = true" );
			}
			assignment.assign( variable, value );
		}
		System.out.println(
				String.format(
						"total map/div/app/disapp events: %d, %d, %d, %d",
						mappings,
						divisions,
						appearances,
						disappearances ) );

		// Dispose of model and environment
		model.dispose();
		env.dispose();

		return new GurobiReadouts( iterCount, solvingTime, objval );
	}

}
