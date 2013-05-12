package fiji.plugin.trackmate.gui.descriptors;

import java.util.Map;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackMateModel;
import fiji.plugin.trackmate.features.FeatureFilter;
import fiji.plugin.trackmate.gui.panels.InitFilterPanel;
import fiji.plugin.trackmate.util.TMUtils;

public class InitFilterDescriptor implements WizardPanelDescriptor {

	public static final String DESCRIPTOR = "InitialThresholding";
	private InitFilterPanel component;
	private final TrackMate trackmate;
	private Map<String, double[]> features;
	
	
	public InitFilterDescriptor(TrackMate trackmate) {
		this.trackmate = trackmate;
	}
	
	
	@Override
	public InitFilterPanel getComponent() {
		return component;
	}

	@Override
	public void aboutToDisplayPanel() {
		TrackMateModel model = trackmate.getModel();
		Settings settings = trackmate.getSettings();
		features = TMUtils.getSpotFeatureValues(model.getSpots(), settings.getSpotFeatures(), model.getLogger());
		component = new InitFilterPanel(features);
		Double initialFilterValue = trackmate.getSettings().initialSpotFilterValue;
		component.setInitialFilterValue(initialFilterValue);
	}

	@Override
	public void displayingPanel() {	}

	@Override
	public void aboutToHidePanel() {
		
		component.quit();
		
		final TrackMateModel model = trackmate.getModel();
		Logger logger = model.getLogger();
		FeatureFilter initialThreshold = component.getFeatureThreshold();
		String str = "Initial thresholding with a quality threshold above "+ String.format("%.1f", initialThreshold.value) + " ...\n";
		logger.log(str,Logger.BLUE_COLOR);
		int ntotal = model.getSpots().getNSpots(false);
		trackmate.getSettings().initialSpotFilterValue = initialThreshold.value;
		trackmate.execInitialSpotFiltering();
		int nselected = model.getSpots().getNSpots(false);
		logger.log(String.format("Retained %d spots out of %d.\n", nselected, ntotal));
		
		/*
		 * We have some spots so we need to compute spot features will we render them.
		 */
		logger.log("Calculating spot features...\n",Logger.BLUE_COLOR);
		// Calculate features
		long start = System.currentTimeMillis();
		trackmate.computeSpotFeatures(true);		
		long end  = System.currentTimeMillis();
		logger.log(String.format("Calculating features done in %.1f s.\n", (end-start)/1e3f), Logger.BLUE_COLOR);
	}
}