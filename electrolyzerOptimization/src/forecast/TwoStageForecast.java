package forecast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Class TwoStageForcast.
 */
public class TwoStageForecast {

	/** The number of first stage period. */
	int numberOfFirstStagePeriod = -1; 
	
	/** The forecast first stage. */
	List<Double> forecastFirstStage = new ArrayList<Double>();  	
	
	Map<Integer, List<Double>> shortTermForecastsByPeriod = new HashMap<>();
	/**
	 * Gets the number of first stage period.
	 *
	 * @return the numberOfFirstStagePeriod
	 */
	public int getNumberOfFirstStagePeriod() {
		return numberOfFirstStagePeriod;
	}
	
	/**
	 * Sets the number of first stage period.
	 *
	 * @param numberOfFirstStagePeriod the numberOfFirstStagePeriod to set
	 */
	public void setNumberOfFirstStagePeriod(int numberOfFirstStagePeriod) {
		this.numberOfFirstStagePeriod = numberOfFirstStagePeriod;
	}
	
	/**
	 * Gets the forecast first stage.
	 *
	 * @return the forecastFirstStage
	 */
	public List<Double> getForecastFirstStage() {
		return forecastFirstStage;
	}
	
	/**
	 * Sets the forecast first stage.
	 *
	 * @param forecastFirstStage the forecastFirstStage to set
	 */
	public void setForecastFirstStage(List<Double> forecastFirstStage) {
		this.forecastFirstStage = forecastFirstStage;
	}

	/**
	 * @return the shortTermForecastsByPeriod
	 */
	public Map<Integer, List<Double>> getShortTermForecastsByPeriod() {
		return shortTermForecastsByPeriod;
	}

	/**
	 * @param shortTermForecastsByPeriod the shortTermForecastsByPeriod to set
	 */
	public void setShortTermForecastsByPeriod(Map<Integer, List<Double>> shortTermForecastsByPeriod) {
		this.shortTermForecastsByPeriod = shortTermForecastsByPeriod;
	}
	

}
