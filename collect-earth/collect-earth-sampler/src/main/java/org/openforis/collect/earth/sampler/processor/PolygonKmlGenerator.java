package org.openforis.collect.earth.sampler.processor;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openforis.collect.earth.sampler.model.SimpleCoordinate;
import org.openforis.collect.earth.sampler.model.SimplePlacemarkObject;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.bytecode.opencsv.CSVReader;

import com.vividsolutions.jts.geom.Point;

public abstract class PolygonKmlGenerator extends KmlGenerator {

	private static final Integer DEFAULT_INNER_POINT_SIDE = 2;
	private Integer innerPointSide;
	private final String host;
	private final String port;
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	public PolygonKmlGenerator(String epsgCode, String host, String port, Integer innerPointSide) {
		super(epsgCode);
		this.host = host;
		this.port = port;
		this.innerPointSide = innerPointSide;
	}

	protected abstract void fillExternalLine(float distanceBetweenSamplePoints, float distancePlotBoundary, double[] coordOriginalPoints,
			SimplePlacemarkObject parentPlacemark) throws TransformException;

	protected abstract void fillSamplePoints(float distanceBetweenSamplePoints, double[] coordOriginalPoints, String currentPlaceMarkId,
			SimplePlacemarkObject parentPlacemark) throws TransformException;

	abstract int getNumOfRows();

	protected int getPointSide() {
		if (innerPointSide == null) {
			innerPointSide = DEFAULT_INNER_POINT_SIDE;
		}
		return innerPointSide;
	}

	protected List<SimpleCoordinate> getSamplePointPolygon(double[] topLeftPosition, int samplePointSide) throws TransformException {
		final List<SimpleCoordinate> coords = new ArrayList<SimpleCoordinate>();
		coords.add(new SimpleCoordinate(topLeftPosition)); // TOP-LEFT
		coords.add(new SimpleCoordinate(getPointWithOffset(topLeftPosition, samplePointSide, 0))); // TOP-RIGHT
		coords.add(new SimpleCoordinate(getPointWithOffset(topLeftPosition, samplePointSide, samplePointSide))); // BOTTOM-RIGHT
		coords.add(new SimpleCoordinate(getPointWithOffset(topLeftPosition, 0, samplePointSide))); // BOTTOM-LEFT

		// close the square
		coords.add(new SimpleCoordinate(topLeftPosition)); // TOP-LEFT
		return coords;
	}

	@Override
	protected Map<String, Object> getTemplateData(String csvFile, float distanceBetweenSamplePoints, float distancePlotBoundary) throws IOException {
		final Map<String, Object> data = new HashMap<String, Object>();

		SimplePlacemarkObject previousPlacemark = null;

		Rectangle2D viewFrame = new Rectangle2D.Float();
		boolean firstPoint = true;
		// Read CSV file so that we can store the information in a Map that can
		// be used by freemarker to do the "goal-replacement"
		String[] csvRow;

		CSVReader reader = null;
		List<SimplePlacemarkObject> placemarks = null;
		try {
			reader = getCsvReader(csvFile);
			placemarks = new ArrayList<SimplePlacemarkObject>();
			while ((csvRow = reader.readNext()) != null) {
				try {

					final PlotProperties plotProperties = getPlotProperties(csvRow);

					final Point transformedPoint = transformToWGS84(plotProperties.xCoord, plotProperties.yCoord); // TOP-LEFT

					if (firstPoint) {
						viewFrame.setRect(transformedPoint.getX(), transformedPoint.getY(), 0, 0);
						firstPoint = false;
					} else {
						final Rectangle2D rectTemp = new Rectangle2D.Float();
						rectTemp.setRect(transformedPoint.getX(), transformedPoint.getY(), 0, 0);
						viewFrame = viewFrame.createUnion(rectTemp);
					}

					// This should be the position at the center of the plot
					final double[] coordOriginalPoints = new double[] { transformedPoint.getX(), transformedPoint.getY() };
					// Since we use the coordinates with TOP-LEFT anchoring then
					// we
					// need to move the #original point@ to the top left so that
					// the center ends up being the expected original coord

					final SimplePlacemarkObject parentPlacemark = new SimplePlacemarkObject(transformedPoint.getCoordinate(), plotProperties.id,
							plotProperties.elevation, plotProperties.slope, plotProperties.aspect, getHumanReadableAspect(plotProperties.aspect));

					if (previousPlacemark != null) {
						// Give the current ID to the previous placemark so that
						// we can move from placemark to placemark
						previousPlacemark.setNextPlacemarkId(plotProperties.id);
					}

					previousPlacemark = parentPlacemark;

					fillSamplePoints(distanceBetweenSamplePoints, coordOriginalPoints, plotProperties.id, parentPlacemark);

					fillExternalLine(distanceBetweenSamplePoints, distancePlotBoundary, coordOriginalPoints, parentPlacemark);

					placemarks.add(parentPlacemark);

				} catch (final NumberFormatException e) {
					logger.error("Error in the number formatting", e);
				} catch (final Exception e) {
					logger.error("Error in the number formatting", e);
				}
			}
		} catch (final Exception e) {
			logger.error("Error reading CSV", e);
		} finally {
			if (reader != null) {
				reader.close();
			}
		}

		data.put("placemarks", placemarks);
		data.put("region_north", viewFrame.getMaxY() + "");
		data.put("region_south", viewFrame.getMinY() + "");
		data.put("region_west", viewFrame.getMinX() + "");
		data.put("region_east", viewFrame.getMaxX() + "");
		data.put("region_center_X", viewFrame.getCenterX() + "");
		data.put("region_center_Y", viewFrame.getCenterY() + "");
		data.put("host", KmlGenerator.getHostAddress(host, port));
		data.put("plotFileName", KmlGenerator.getCsvFileName(csvFile));
		return data;
	}

}
