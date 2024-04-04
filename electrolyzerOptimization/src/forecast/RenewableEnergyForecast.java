package forecast;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class RenewableEnergyForecast {

	public static void main(String[] args) {

		int selectedPeriod = 1; // Set the long-term period here
		int arrayLength = 10; 
		RenewableEnergyForecast.runForecasting(arrayLength);
	}


	public static List<TwoStageForecast> runForecasting (int arrayLength) {
		String excelFilePath = "src/input/Power_Renewable_Energies.xlsx";
		List<TwoStageForecast> listOfForecast = new  ArrayList<TwoStageForecast>(); 

		List<Double> longTermForecasts = new ArrayList<>();
		Map<Integer, List<Double>> shortTermForecastsByPeriod = new HashMap<>();
		Map<Integer, List<Double>> updatedForecastsByPeriod = new HashMap<>();

		try {
			processExcelData(excelFilePath, longTermForecasts, shortTermForecastsByPeriod);

			writeForecastsToFile("src/output/ForecastResults.csv", longTermForecasts, shortTermForecastsByPeriod); //Writes all Forecasts
			Map<Integer, List<Double>> updatedLongTermForecastsByPeriod = generateUpdatedLongTermForecasts(longTermForecasts);

			for (int period = 0; period < arrayLength; period++) {
				TwoStageForecast forecast = new TwoStageForecast(); 
				updatedForecastsByPeriod = updateShortTermForecasts(period+1, shortTermForecastsByPeriod);
				forecast.setNumberOfFirstStagePeriod(period);
				forecast.setForecastFirstStage(updatedLongTermForecastsByPeriod.get(period+1));
				forecast.setShortTermForecastsByPeriod(updatedForecastsByPeriod);
				listOfForecast.add(forecast);
			}

			// Serialize the forecast object to JSON and save it to a file
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			try (FileWriter writer = new FileWriter("src/output/List_ForecastResults.json")) {
				gson.toJson(listOfForecast, writer);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		return listOfForecast; 
	}

	private static void processExcelData(String excelFilePath, List<Double> longTermForecasts, Map<Integer, List<Double>> shortTermForecastsByPeriod) throws IOException {
		try (FileInputStream fis = new FileInputStream(excelFilePath); Workbook workbook = new XSSFWorkbook(fis)) {
			Sheet sheet = workbook.getSheetAt(0);
			final double secondsPerStep = 0.025 * 3600;
			double sum = 0;
			int count = 0;
			int currentPeriod = 1;
			double lastTime = 0;
			double intervalStart = 0;
			int lastPeriod = 0;
			//            int lastRow = sheet.getLastRowNum();
			int lastRow = 9002;
			final double STD_DEVIATION = 0.05;
			final int TOTAL_PERIODS = 10;

			for (int rowNum = 2; rowNum <= lastRow; rowNum++) {
				Row row = sheet.getRow(rowNum);
				if (row != null) {
					Cell periodCell = row.getCell(0);
					Cell timeCell = row.getCell(2);
					Cell powerCell = row.getCell(16);

					int period = (int) periodCell.getNumericCellValue();
					double currentTime = timeCell.getNumericCellValue();

					if (currentPeriod != lastPeriod) {
						lastPeriod = currentPeriod;
						sum = 0;
						count = 0;
						intervalStart = 0;
					}

					List<Double> forecasts = shortTermForecastsByPeriod.computeIfAbsent(currentPeriod, k -> new ArrayList<>());

					if (currentTime >= lastTime + secondsPerStep) {
						double forecastValue = count > 0 ? sum / count : 0;
						forecasts.add(forecastValue);
						sum = powerCell.getNumericCellValue();
						count = 1;
						lastTime += secondsPerStep;
					} else {
						sum += powerCell.getNumericCellValue();
						count++;
					}

					if (period != currentPeriod) {
						if (count > 0) {
							double intervalAverage = sum / count;
							longTermForecasts.add(intervalAverage);
							sum = powerCell.getNumericCellValue();
							count = 1;
						}
						currentPeriod = period;
					} else {
						sum += powerCell.getNumericCellValue();
						count++;
					}
				}
			}
		}
	}


	private static Map<Integer, List<Double>> updateShortTermForecasts(int selectedPeriod, Map<Integer, List<Double>> shortTermForecastsByPeriod) {
		Random random = new Random();
		final double STD_DEVIATION = 0.05;
		final int TOTAL_UPDATES = 10;

		List<Double> forecasts = shortTermForecastsByPeriod.get(selectedPeriod);
		if (forecasts == null) {
			throw new IllegalArgumentException("No forecasts found for the selected period: " + selectedPeriod);
		}

		// Create a new HashMap for the updated forecast values
		Map<Integer, List<Double>> updatedForecastsByPeriod = new HashMap<>();

		// Für jedes Update...
		for (int update = 1; update <= TOTAL_UPDATES; update++) {
			List<Double> updatedForecasts = new ArrayList<>();
			// Aktualisiere die aktuellen Prognosen
			for (int i = 0; i < forecasts.size(); i++) {
				double originalForecast;
				if (update == 1) {
					originalForecast = forecasts.get(i); // Unveränderte Werte für frühere Updates übernehmen
				} 
				else if (i < update-1) {
					originalForecast = updatedForecastsByPeriod.get(update - 1).get(i); // Werte aus dem vorherigen Update-Zyklus übernehmen
				}
				else {
					// Berechnung der Schwankung für nachfolgende Updates
					double fluctuation = STD_DEVIATION * forecasts.get(i) * random.nextGaussian();
					originalForecast = forecasts.get(i) + fluctuation;
				}
				updatedForecasts.add(originalForecast);
			}
			updatedForecastsByPeriod.put(update, updatedForecasts); // Füge die aktualisierten Prognosewerte zur neuen HashMap hinzu
		}

//		for (Map.Entry<Integer, List<Double>> entry : updatedForecastsByPeriod.entrySet()) {     int period = entry.getKey();     List<Double> forecasts1 = entry.getValue();     System.out.println("Update " + period + " forecasts:");     for (int i = 0; i < forecasts1.size(); i++) {         System.out.printf("Wert %d: %.15f%n", i, forecasts1.get(i));     } }

		return updatedForecastsByPeriod;
	}


	private static Map<Integer, List<Double>> generateUpdatedLongTermForecasts(List<Double> longTermForecasts) {
		Random random = new Random();
		final double STD_DEVIATION = 0.05;
		final int TOTAL_UPDATES = 10;

		// Erstelle eine neue HashMap für die aktualisierten Prognosewerte
		Map<Integer, List<Double>> updatedLongTermForecastsByPeriod = new HashMap<>();

		// Für jedes Update...
		for (int update = 1; update <= TOTAL_UPDATES; update++) {
			List<Double> updatedForecasts = new ArrayList<>();
			// Aktualisiere die aktuellen Prognosen
			for (int i = 0; i < longTermForecasts.size(); i++) {
				double originalForecast;
				if (update == 1) {
					originalForecast = longTermForecasts.get(i); // Unveränderte Werte für frühere Updates übernehmen
				} 
				else if (i < update-1) {
					originalForecast = updatedLongTermForecastsByPeriod.get(update - 1).get(i); // Werte aus dem vorherigen Update-Zyklus übernehmen
				}
				else {
					// Berechnung der Schwankung für nachfolgende Updates
					double fluctuation = STD_DEVIATION * longTermForecasts.get(i) * random.nextGaussian();
					originalForecast = longTermForecasts.get(i) + fluctuation;
				}
				updatedForecasts.add(originalForecast);
			}
			updatedLongTermForecastsByPeriod.put(update, updatedForecasts); // Füge die aktualisierten Prognosewerte zur neuen HashMap hinzu
		}

		return updatedLongTermForecastsByPeriod;
	}

	private static void writeForecastsToFile(String fileName, List<Double> longTermForecasts, Map<Integer, List<Double>> shortTermForecastsByPeriod) throws IOException {
		try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
			// Schreibe die langfristigen Prognosen
			writer.println("Periode;Langfristige Prognose");
			for (int i = 0; i < longTermForecasts.size(); i++) {
				writer.printf("%d;%.3f%n", i + 1, longTermForecasts.get(i));
			}
			writer.println(); // Leerzeile einfügen

			// Schreibe die kurzfristigen Prognosen
			writer.println("Periode;Zeitintervall;ShortTermPrognose");
			for (Map.Entry<Integer, List<Double>> entry : shortTermForecastsByPeriod.entrySet()) {
				int period = entry.getKey();
				List<Double> forecasts = entry.getValue();
				for (int i = 0; i < forecasts.size(); i++) {
					double startTime = i * 0.025 * 60; // Startzeit des Intervalls in Minuten
					double endTime = (i + 1) * 0.025 * 60; // Endzeit des Intervalls in Minuten
					writer.printf("%d;%.1f-%.1f;%.3f%n", period, startTime, endTime, forecasts.get(i));
				}
			}
			System.out.println("Die Prognosen wurden erfolgreich in '" + fileName + "' geschrieben.");
		}
	}
}
