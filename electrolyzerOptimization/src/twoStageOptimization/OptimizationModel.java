package twoStageOptimization;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.lang.reflect.Type;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import designpatterns.DesignPatterns;
import designpatterns.OptimizationResults;
import designpatterns.ResourceParameters;
import forecast.RenewableEnergyForecast;
import forecast.TwoStageForecast;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.UnknownObjectException;
import systemParameterExtraction.ReadParametersFromDataModel;
import systemParameterModel.Dependency;
import systemParameterModel.SystemParameters;

public class OptimizationModel {
	/** The nolimit. */
	static final double NOLIMIT = 9999;

	/** The Constant INPUT. */
	static final String INPUT = "Input";

	/** The Constant OUTPUT. */
	static final String OUTPUT = "Output";

	/** The Constant SOC. */
	static final String SOC = "SOC";

	/** The Constant POWER. */
	static final String POWER = "Power";

	/** The Constant BINARY. */
	static final String BINARY = "Binary";

	/** The Constant SEGMENT. */
	static final String SEGMENT = "Segment";

	/** The Constant STATE. */
	static final String STATE = "State";

	static final int ONLYONE = -1; 

	static double startupCost = 10; 
	static double constHydrDemand = 900; 

	/** The global system parameters. */
	static SystemParameters globalSystemParameters = new SystemParameters();

	public static void main(String[] args)  {

		generateTwoStageModel();
	}

	/**
	 * Sets the optimization parameters, primarily in ArrayList<ResourceParameters> resourceParameters.
	 */
	public static void setOptimizationParameters () {

		SystemParameters systemParameters = new SystemParameters();
		String filePath = "src/input/systemParameters_Electrolyzers2.json"; 
		systemParameters = ReadParametersFromDataModel.readJson(filePath);
		if (systemParameters == null) System.err.println("SystemParameters empty");

		setGlobalSystemParameters(systemParameters);

		for (int resource = 0; resource < systemParameters.getResourceParameters().size(); resource++) {
			ResourceParameters resourceParameters = new ResourceParameters();
			resourceParameters = systemParameters.getResourceParameters().get(resource);
			resourceParameters.setNumberOfSystemStates(resourceParameters.getSystemStates().size());
			DesignPatterns.getResourceParameters().add(resourceParameters);
		}

		designpatterns.DesignPatterns.setOptimalityGap(0.001); // default 10e-4 = 0.001
	}


	/**
	 * Generate RTO model.
	 */
	public static void generateTwoStageModel() {
		//		List<OptimizationResults> historicResults = new ArrayList<>();

		final int arrayLength = 10; // Total number of runs for the first stage.
		double timeStepLength; // Time interval length, will be set dynamically.

		List<TwoStageForecast> forecast = getForecast(arrayLength);

		List<OptimizationResults> firstStageResults = new ArrayList<>();
		for (int firstStagePeriod = 0; firstStagePeriod < arrayLength; firstStagePeriod++) {
			List<OptimizationResults> RTOResults = new ArrayList<>();
			// First stage optimization
			timeStepLength = globalSystemParameters.getTemporalResolutionOptimizationModel();
			try {
				setOptimizationParameters();
				//				firstStageResults = setupOptModel(arrayLength, timeStepLength, historicResults, firstStagePeriod, firstStagePeriod, -1, forecast.get(firstStagePeriod), null);

				firstStageResults = setupOptModel(arrayLength, timeStepLength, firstStageResults, firstStagePeriod, firstStagePeriod, -1, forecast.get(firstStagePeriod), null);
				System.out.println("First Stage Iteration: " + (firstStagePeriod + 1));

				// Check if there are any results, and insert the result corresponding to the current period `i` into `RTOResults`.
				if (!firstStageResults.isEmpty()) {

					for (int i = 0; i<firstStageResults.size(); i++) {
						OptimizationResults resNew = firstStageResults.get(i);
						resNew.setOptimizationResult(resNew.getOptimizationResults().get(firstStagePeriod));
						//						resNew.setOptimizationResults(resNew.getOptimizationResults().subList(firstStagePeriod, firstStagePeriod+1));
						RTOResults.add(resNew); // Adds the cloned result at the first position of RTOResults
					}

				}

				// Methods for converting the first stage results into initial values and historical data.
				//				historicResults = updateHistoricValuesByVariableName(firstStageResults, historicResults);

			} catch (IloException e) {
				e.printStackTrace();
			}


			// Second stage optimization, conducted immediately after each first stage iteration.
			timeStepLength = 0.025;
			int arrayLengthRTO = 10; //(int) (globalSystemParameters.getTemporalResolutionOptimizationModel() / timeStepLength);
			final int secondStageIterations = 10; //arrayLengthRTO;//(int) (globalSystemParameters.getTemporalResolutionOptimizationModel()/timeStepLength); // Number of passes for the second stage per pass of the first stage.

			for (int rtoPeriod = 0; rtoPeriod < secondStageIterations; rtoPeriod++) {
				try {
					// TODO Problem, Werte der vorigen RTO periode werden nicht übernommen
					// TODO Check, ob in zweiter First stage periode historische werte genommen werden


					// TODO nur Zustände übergeben
					RTOResults = setupOptModel(arrayLengthRTO, timeStepLength, RTOResults, rtoPeriod, firstStagePeriod, rtoPeriod, forecast.get(firstStagePeriod), firstStageResults);
					System.out.println("Second Stage Iteration: " + (rtoPeriod + 1) + " of First Stage Iteration: " + (firstStagePeriod + 1));
				} catch (IloException e) {
					e.printStackTrace();
				}
			}
			// TODO auch nur zustände, t_tauk_9 = tau_k
			//			historicResults = updateHistoricValuesWithRTO(RTOResults, historicResults, firstStagePeriod);
			// historicResults.addAll(RTOResults); // Exemplary, adapt to your logic.
		}
	}

	/**
	 * Optimization  model.
	 *
	 * @param arrayLength the array length
	 * @param timeStepLength the time step length
	 * @param results the results
	 * @param counterPreviousTimeSteps the counter previous time steps
	 * @param firstStagePeriod the first stage period
	 * @param rtoPeriod the rto period
	 * @param forecast 
	 * @param firstStageResults 
	 * @return the list
	 * @throws IloException the ilo exception
	 */
	public static List<OptimizationResults> setupOptModel (
			int arrayLength, 
			double timeStepLength, 
			List<OptimizationResults> results, 
			int counterPreviousTimeSteps, 
			int firstStagePeriod, 
			int rtoPeriod, 
			TwoStageForecast forecast, 
			List<OptimizationResults> firstStageResults) 
					throws IloException {

		String nameOfModel = ""; 
		if (rtoPeriod == -1) {
			nameOfModel = Integer.toString(firstStagePeriod);
		} else {
			nameOfModel = Integer.toString(firstStagePeriod)+"-"+Integer.toString(rtoPeriod);
		}


		List<OptimizationResults> newResults = null;
		try {
			//set arraylength and timestep length (=temp. res)
			DesignPatterns.setArrayLength(arrayLength);
			DesignPatterns.setTimeInterval(timeStepLength);


			IloNumVar[] sumPowerInput = DesignPatterns.getCplex().numVarArray(DesignPatterns.getArrayLength(),  0 ,  Double.MAX_VALUE);
			DesignPatterns.getDecisionVariablesVector().put("sumPowerInput", sumPowerInput);

			IloNumVar[] renewableGeneration = DesignPatterns.getCplex().numVarArray(DesignPatterns.getArrayLength(),  0 ,  Double.MAX_VALUE);
			DesignPatterns.getDecisionVariablesVector().put("renewableGeneration", renewableGeneration);		


			//-------------------------------------------------------------------- Decision Variables --------------------------------------------------------------------
			double maxPowerSystem; 
			try {
				maxPowerSystem = getGlobalSystemParameters().getMaxPowerSystemInput().get(0); 
			} catch (Exception e) {
				maxPowerSystem =  Double.MAX_VALUE;
			}

			designpatterns.DesignPatterns.creationOfDecisionVariables(maxPowerSystem);

			// ------------------------------------------------------------------------ Use of Design Patterns--------------------------------------------------------------------

			// ------------------------------------------------------------------------ Parameterize Design patterns based on parameter set --------------------------------------------------------------------

			// Parameterize resource models
			for (int resource=0; resource < getGlobalSystemParameters().getResourceParameters().size(); resource++) {
				ResourceParameters resourceParameters = getGlobalSystemParameters().getResourceParameters().get(resource);
				String nameOfResource = resourceParameters.getName();
				if (resourceParameters.isSecondaryResource()==false 
						&& (
								!(resourceParameters.getPlaList().isEmpty())
								|| !(resourceParameters.getSlope() == 0)
								)
						){
					designpatterns.DesignPatterns.generateInputOutputRelationship(nameOfResource);
				} else {
					designpatterns.DesignPatterns.generateEnergyBalanceForStorageSystem(nameOfResource);
				}

				if (!(resourceParameters.getSystemStates().isEmpty())) {
					designpatterns.DesignPatterns.generateSystemStateSelectionByPowerLimits(nameOfResource);
					designpatterns.DesignPatterns.generateStateSequencesAndHoldingDuration(nameOfResource);
					designpatterns.DesignPatterns.generateRampLimits(nameOfResource, INPUT);
				}
			}

			for (OptimizationResults optRes : results) {
				String variableName = optRes.getVariableName();
				boolean matrixVariable = false; 
				int stateOrSegmentCounter = 0;
				if (variableName.contains(STATE) || variableName.contains(SEGMENT) || variableName.contains(BINARY) ) {
					matrixVariable = true; 
					//						variableName = variableName.substring(0, variableName.indexOf("-"));
					//						stateOrSegmentCounter = Integer.parseInt(variableName.substring(variableName.indexOf("-")));
					stateOrSegmentCounter = extractNumber(variableName); 
					variableName = extractString(variableName);
				} 
				// Assign the parts to corresponding variables
				// Ensure that the variableName format is always correct (contains 3 "-" characters)

				// für alle Zeitschritte <  counter alle Werte über Nebenbedingungen fixieren

				for (int timeStep = 0; timeStep < counterPreviousTimeSteps; timeStep++) {
					double decVarValueForTimeStep = optRes.getOptimizationResults().get(timeStep);
					try {
						if (matrixVariable == false) {
							if (!(variableName.contains("renewableGeneration")) || rtoPeriod != -1){
								designpatterns.DesignPatterns.getCplex().addEq(
										decVarValueForTimeStep, 
										designpatterns.DesignPatterns.getDecisionVariablesVector().get(variableName)[timeStep]
										);
							}
						} else {
							if (variableName.contains(STATE)) {
								designpatterns.DesignPatterns.getCplex().addEq(decVarValueForTimeStep, 
										designpatterns.DesignPatterns.getDecisionVariablesMatrix().get(variableName)[timeStep][stateOrSegmentCounter]);
							}
							else {
								//	 SEGMENT
								//								designpatterns.DesignPatterns.getCplex().addEq(decVarValueForTimeStep, 
								//										designpatterns.DesignPatterns.getDecisionVariablesMatrix().get(variableName)[stateOrSegmentCounter][timeStep]);
							}
						}
					} catch (Exception e) {
						System.err.println(e);
						e.printStackTrace();
					}
				}
			}



			// ------------------------------------------------------------------------ CONSTRAINTS--------------------------------------------------------------------

			// Constraint to achieve energy equality if in RTO
			if (rtoPeriod!=-1) {
				IloNumExpr sumInputRTO = DesignPatterns.getCplex().numExpr();

				for (int rtoCounter = 0; rtoCounter < DesignPatterns.getArrayLength(); rtoCounter++) {
					sumInputRTO = DesignPatterns.getCplex().sum(
							sumInputRTO, 
							DesignPatterns.getCplex().prod(
									timeStepLength, 
									designpatterns.DesignPatterns.getDecisionVariableFromVector("System", INPUT, ONLYONE, POWER)[rtoCounter]
									)
							);
				}

				for (OptimizationResults oneRes: firstStageResults) {
					if (oneRes.getVariableName().equals("System-Input-Power")) {
						System.out.println(oneRes.getOptimizationResults().get(firstStagePeriod) + " " + oneRes.getOptimizationResults().get(firstStagePeriod)*0.25);
						DesignPatterns.getCplex().addEq(
								sumInputRTO, 
								0.25*oneRes.getOptimizationResults().get(firstStagePeriod)
								);						
					}
				}
			}

			if (rtoPeriod == -1) {
				for (int elec = 0; elec < DesignPatterns.getResourceParameters().size(); elec++) {
					IloNumExpr variableSum = DesignPatterns.getCplex().numExpr();
					String varName = DesignPatterns.getResourceParameters().get(elec).getName()+"-Input-0-Power";
					for (int timeStep = 0; timeStep < DesignPatterns.getArrayLength(); timeStep++) {
						variableSum = DesignPatterns.getCplex().sum(
								variableSum, 
								DesignPatterns.getCplex().prod(
										DesignPatterns.getTimeInterval(), 
										// decVar
										//DesignPatterns.getDecisionVariablesVector().get("Electrolyzer1-Input-0-Power")[timeStep]
										DesignPatterns.getDecisionVariablesVector().get(varName)[timeStep]
										)
								);
					}
					DesignPatterns.getCplex().addEq(variableSum, 4.5); // 4.5
				}
			}


			// incorporate forecast of RE

			for (int timeStep = 0; timeStep < DesignPatterns.getArrayLength(); timeStep++) {
				double forecastForTimeStep; 
				if (rtoPeriod == -1) {
					forecastForTimeStep = forecast.getForecastFirstStage().get(timeStep);
					//					System.out.println(forecastForTimeStep);
				} else {
					forecastForTimeStep = forecast.getShortTermForecastsByPeriod().get(rtoPeriod+1).get(timeStep);
				}

				DesignPatterns.getCplex().addEq(
						designpatterns.DesignPatterns.getDecisionVariablesVector().get("renewableGeneration")[timeStep],
						forecastForTimeStep
						);
			}

			// Set up and add dependencies

			designpatterns.DesignPatterns.generateCorrelativeDependency(
					new IloNumVar[][] {designpatterns.DesignPatterns.getDecisionVariableFromVector("System", INPUT, ONLYONE, POWER), 
						designpatterns.DesignPatterns.getDecisionVariablesVector().get("renewableGeneration")	
					}, 
					new IloNumVar[][] {designpatterns.DesignPatterns.getDecisionVariablesVector().get("sumPowerInput")}
					);

			designpatterns.DesignPatterns.generateCorrelativeDependency(
					new IloNumVar[][] {designpatterns.DesignPatterns.getDecisionVariablesVector().get("sumPowerInput")}, 
					//					new IloNumVar[][] {designpatterns.DesignPatterns.getDecisionVariableFromVector("System", INPUT, ONLYONE, POWER)},
					new IloNumVar[][] {
						designpatterns.DesignPatterns.getDecisionVariableFromVector("Electrolyzer1", INPUT, 0, POWER), 
						designpatterns.DesignPatterns.getDecisionVariableFromVector("Electrolyzer2", INPUT, 0, POWER), 
						designpatterns.DesignPatterns.getDecisionVariableFromVector("Electrolyzer3", INPUT, 0, POWER), 
					}
					);

			designpatterns.DesignPatterns.generateCorrelativeDependency(
					new IloNumVar[][] {
						designpatterns.DesignPatterns.getDecisionVariableFromVector("Electrolyzer1", OUTPUT, ONLYONE,  POWER), 
						designpatterns.DesignPatterns.getDecisionVariableFromVector("Electrolyzer2", OUTPUT, ONLYONE,  POWER), 
						designpatterns.DesignPatterns.getDecisionVariableFromVector("Electrolyzer3", OUTPUT, ONLYONE,  POWER) 
					},
					new IloNumVar[][] {designpatterns.DesignPatterns.getDecisionVariableFromVector("System", OUTPUT, ONLYONE, POWER)}
					);


			// set objective function 
			IloLinearNumExpr objective = designpatterns.DesignPatterns.getCplex().linearNumExpr();
			if (rtoPeriod == -1) {
				for (int i = 0; i < designpatterns.DesignPatterns.getArrayLength(); i++) {
					objective.addTerm(
							designpatterns.DesignPatterns.getTimeInterval()*designpatterns.DesignPatterns.getElectricityPrice()[i], 
							designpatterns.DesignPatterns.getDecisionVariableFromVector("System", INPUT, ONLYONE, POWER)[i]
							);
				}
				designpatterns.DesignPatterns.getCplex().addMinimize(objective);
			} else {
				for (int i = 0; i < designpatterns.DesignPatterns.getArrayLength(); i++) {
					objective.addTerm(
							1, 
							designpatterns.DesignPatterns.getDecisionVariableFromVector("System", OUTPUT, ONLYONE, POWER)[i]
							);
				}
				designpatterns.DesignPatterns.getCplex().addMaximize(objective);

			}

			if (rtoPeriod == -1) 	DesignPatterns.getCplex().exportModel("model"+Integer.toString(firstStagePeriod)+".lp");

			// solver specific parameters
			designpatterns.DesignPatterns.getCplex().setParam(IloCplex.Param.MIP.Tolerances.MIPGap, designpatterns.DesignPatterns.getOptimalityGap());
			long start = System.currentTimeMillis();
			System.out.println("cplex solve");
			if (designpatterns.DesignPatterns.getCplex().solve()) {
				long end = System.currentTimeMillis();
				long solvingTime = 	(end - start);
				System.out.println("obj = "+designpatterns.DesignPatterns.getCplex().getObjValue());
				//				System.out.println("solvingTime in ms = "+solvingTime);
				//				System.out.println(designpatterns.DesignPatterns.getCplex().getCplexStatus());

				newResults = saveResults();
				String filePath = "src/output/";
				writeResultsFromListToFile(newResults, nameOfModel, filePath);

			} else {
				System.out.println("Model not solved");
			}
		}

		catch (IloException exc) {
			exc.printStackTrace();
		}
		finally {
			if (designpatterns.DesignPatterns.getCplex()!=null)  {
				designpatterns.DesignPatterns.getCplex().close();
				designpatterns.DesignPatterns.globalCplex=null;
			}
		}
		return newResults;
	}


	public static void setUpDependencies() throws IloException {
		int depCounter = 0; 
		for (Dependency dependency: getGlobalSystemParameters().getDependencies()) {
			//			System.out.println("Dependency"+ depCounter);

			List<IloNumVar[]> inputDecVar = new ArrayList<IloNumVar[]>(); 
			List<IloNumVar[]> outputDecVar = new ArrayList<IloNumVar[]>(); 

			for (int inputCounter = 0; inputCounter < dependency.getRelevantInputs().size(); inputCounter++) {
				String nameOfInput = dependency.getRelevantInputs().get(inputCounter);
				int numberOfInput = -1; 
				//				System.out.println("input: " + nameOfInput);
				if (nameOfInput.contains("SystemOutput")){
					numberOfInput = Integer.parseInt(nameOfInput.substring(nameOfInput.indexOf("-")));
					nameOfInput = "System";
					IloNumVar[] input;
					if (numberOfInput == 0) {
						input = DesignPatterns.getDecisionVariableFromVector(nameOfInput, OUTPUT, ONLYONE, POWER);
					}
					else {						
						input = DesignPatterns.getCplex().numVarArray(
								DesignPatterns.getArrayLength(),  
								0,
								Double.MAX_VALUE
								//								getGlobalSystemParameters().getMinPowerSystemInput().get(numberOfInput), 
								//								getGlobalSystemParameters().getMaxPowerSystemInput().get(numberOfInput)
								);
						DesignPatterns.getDecisionVariablesVector().put("System"+"-"+OUTPUT+"-"+Integer.toString(inputCounter)+POWER, input);
					}
					inputDecVar.add(input);
				} else {
					IloNumVar[] input = DesignPatterns.getDecisionVariableFromVector(nameOfInput, INPUT, 0, POWER);
					inputDecVar.add(input);
				}
			}

			for (int outputCounter = 0; outputCounter < dependency.getRelevantOutputs().size(); outputCounter++) {
				String nameOfOutput = dependency.getRelevantOutputs().get(outputCounter);
				//				System.out.println("output: " + nameOfOutput);
				int numberOfOutput = -1; 
				if (nameOfOutput.contains("SystemInput")){
					numberOfOutput = Integer.parseInt(nameOfOutput.substring(nameOfOutput.indexOf("-")));
					nameOfOutput = "System";
					IloNumVar[] output;
					if (numberOfOutput == 0) {
						//"System", INPUT, ONLYONE,POWER
						output = DesignPatterns.getDecisionVariableFromVector(nameOfOutput, INPUT, ONLYONE, POWER);
					} else {
						output = DesignPatterns.getCplex().numVarArray(
								DesignPatterns.getArrayLength(),  
								0,
								getGlobalSystemParameters().getMaxPowerSystemOutput());
						DesignPatterns.getDecisionVariablesVector().put("System"+"-"+INPUT+"-"+Integer.toString(outputCounter)+POWER, output);
					}
					outputDecVar.add(output);
				} else {
					IloNumVar[] output = DesignPatterns.getDecisionVariableFromVector(nameOfOutput, OUTPUT, ONLYONE, POWER);
					outputDecVar.add(output);
				}
			}

			IloNumVar[][] inputVariablesDependency =  convertListToArray(inputDecVar); 
			IloNumVar[][] outputVariablesDependency = convertListToArray(outputDecVar);

			if (dependency.getTypeOfDependency().equals("correlative")) {
				DesignPatterns.generateCorrelativeDependency(outputVariablesDependency, inputVariablesDependency);
			} else {
				// dependency.getTypeOfDependency().equals("restrictive")
				IloIntVar[][][] restrictiveDependency	= DesignPatterns.generateRestrictiveDependency(outputVariablesDependency, inputVariablesDependency);
			}
			depCounter++; 
		}		
	}

	/**
	 * Save results.
	 *
	 * @return the list
	 */
	public static List<OptimizationResults> saveResults () {
		List<OptimizationResults> optimizationResults = new ArrayList<OptimizationResults>();

		// get all decVars from Vector and save results to List
		for (Entry<String, IloNumVar[]> decisionVariableSet: DesignPatterns.getDecisionVariablesVector().entrySet()) {
			OptimizationResults decVarResults = new OptimizationResults();

			String decisionVariableName = decisionVariableSet.getKey();
			IloNumVar[] decisionVariable = decisionVariableSet.getValue();

			decVarResults.setVariableName(decisionVariableName);
			List<Double> decVarValues = new ArrayList<Double>();

			for (int timeStep = 0; timeStep < decisionVariable.length; timeStep++) {
				try {
					decVarValues.add(timeStep, DesignPatterns.getCplex().getValue(decisionVariable[timeStep]));
				} catch (UnknownObjectException e) {
					System.err.println("Value not found for " + decisionVariableName +" at time step: " + timeStep);
					e.printStackTrace();
				} catch (IloException e) {
					System.err.println("Value not found for " + decisionVariableName +" at time step: " + timeStep);
					e.printStackTrace();
				}
			}
			decVarResults.setOptimizationResults(decVarValues);
			optimizationResults.add(decVarResults);
		}

		// get all decVars from Matrix and save results to List
		for (Entry<String, IloNumVar[][]> decisionVariableSet: DesignPatterns.getDecisionVariablesMatrix().entrySet()) {

			String decisionVariableName = decisionVariableSet.getKey();
			IloNumVar[][] decisionVariable = decisionVariableSet.getValue();


			if (decisionVariableName.contains("State")) {
				System.out.println(decisionVariable[0].length);
				for (int width = 0; width < decisionVariable[0].length; width++) {
					OptimizationResults decVarResults = new OptimizationResults();
					decVarResults.setVariableName(decisionVariableName + "-" + Integer.toString(width));
					List<Double> decVarValues = new ArrayList<Double>();

					for (int timeStep = 0; timeStep < decisionVariable.length; timeStep++) {
						try {
							// state variables defined: statesIntArrayResource[timeStep][state] 
							decVarValues.add(timeStep, DesignPatterns.getCplex().getValue(decisionVariable[timeStep][width]));
						} catch (UnknownObjectException e) {
							System.err.println("Value not found for " + decisionVariableName + "[" + width +"] "+" at time step: " + timeStep);
							decVarValues.add(timeStep, (double) -1);
							e.printStackTrace();
						} catch (IloException e) {
							System.err.println("Value not found for " + decisionVariableName + "[" + width +"] "+" at time step: " + timeStep);
							decVarValues.add(timeStep, (double) -1);
							e.printStackTrace();
						}
					}	
					decVarResults.setOptimizationResults(decVarValues);
					optimizationResults.add(decVarResults);
				}


			} else {
				// other variables defined as [width][timestep]
				/*
				for (int width = 0; width < decisionVariable.length; width++) {
					OptimizationResults decVarResults = new OptimizationResults();
					decVarResults.setVariableName(decisionVariableName + "-" + Integer.toString(width));
					List<Double> decVarValues = new ArrayList<Double>();

					for (int timeStep = 0; timeStep < decisionVariable[0].length; timeStep++) {
						try {
							// state variables defined: statesIntArrayResource[timeStep][state] 
							decVarValues.add(timeStep, DesignPatterns.getCplex().getValue(decisionVariable[width][timeStep]));
						} catch (UnknownObjectException e) {
							System.err.println("Value not found for " + decisionVariableName + "[" + width +"] "+" at time step: " + timeStep);
							decVarValues.add(timeStep, (double) -1);
							e.printStackTrace();
						} catch (IloException e) {
							System.err.println("Value not found for " + decisionVariableName + "[" + width +"] "+" at time step: " + timeStep);
							decVarValues.add(timeStep, (double) -1);
							e.printStackTrace();
						}
						decVarResults.setOptimizationResults(decVarValues);
						optimizationResults.add(decVarResults);
					}	
				}
				 */
				// Loop over width
				for (int width = 0; width < decisionVariable.length; width++) {
					OptimizationResults decVarResults = new OptimizationResults();
					decVarResults.setVariableName(decisionVariableName + "-" + Integer.toString(width));
					List<Double> decVarValues = new ArrayList<Double>();

					// Loop over timestep
					for (int timeStep = 0; timeStep < decisionVariable[width].length; timeStep++) {
						try {
							// Adding values to decVarValues for each timestep
							decVarValues.add(DesignPatterns.getCplex().getValue(decisionVariable[width][timeStep]));
						} catch (UnknownObjectException e) {
							System.err.println("Value not found for " + decisionVariableName + "[" + width +"] "+" at time step: " + timeStep);
							decVarValues.add((double) -1);
							e.printStackTrace();
						} catch (IloException e) {
							System.err.println("Value not found for " + decisionVariableName + "[" + width +"] "+" at time step: " + timeStep);
							decVarValues.add((double) -1);
							e.printStackTrace();
						}
					}
					decVarResults.setOptimizationResults(decVarValues);

					// This line is now correctly placed outside the inner loop, so one OptimizationResults object per width
					optimizationResults.add(decVarResults);
				}
			}

		}
		return optimizationResults;
	}

	/**
	 * Write results to file.
	 *
	 * @param optimizationResults the optimization results
	 * @param fileName the file name
	 * @param filePath the file path
	 */
	public static void writeResultsFromListToFile (List<OptimizationResults> optimizationResults, String fileName, String filePath) {
		// Get the current date and time
		LocalDateTime currentDateTime = LocalDateTime.now();
		// Define the desired date and time format
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
		// Format the current date and time using the formatter
		String formattedDateTime = currentDateTime.format(formatter);

		//TODO check if method works as intended!
		double contentToWrite; 
		try {
//			FileWriter myWriter = new FileWriter(filePath+fileName+"_"+formattedDateTime+".csv");
			FileWriter myWriter = new FileWriter(filePath+fileName+".csv");

			String header = "timeStamp"; 
			for (int i = 0; i < optimizationResults.size(); i++) {
				header = header+","+ optimizationResults.get(i).getVariableName();
			}
			myWriter.write(header);
			myWriter.write("\n");
			for (int timeStep = 0; timeStep < DesignPatterns.getArrayLength(); timeStep++) {
				myWriter.write(Double.toString(timeStep).replace(".", ","));
				for(int resultsCounter = 0; resultsCounter < optimizationResults.size(); resultsCounter++) {
					myWriter.write(";"); // Use semicolon as separator
					//myWriter.write(Double.toString(contentToWrite[i][j]));
					contentToWrite = optimizationResults.get(resultsCounter).getOptimizationResults().get(timeStep);
					myWriter.write(Double.toString(contentToWrite).replace(".", ",")); // Replace decimal point with comma
				}
				myWriter.write("\n");
			}
			myWriter.close();
			System.out.println("Successfully wrote data to the file "+ fileName+".csv.");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @return the globalSystemParameters
	 */
	public static SystemParameters getGlobalSystemParameters() {
		return globalSystemParameters;
	}

	/**
	 * @param globalSystemParameters the globalSystemParameters to set
	 */
	public static void setGlobalSystemParameters(SystemParameters globalSystemParameters) {
		OptimizationModel.globalSystemParameters = globalSystemParameters;
	}

	private static double[] importTSD(String filePath) {
		List<Double> dataList = new ArrayList<>();

		try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
			String line;
			while ((line = reader.readLine()) != null) {
				// Assuming the CSV contains only one column of numerical values
				double value = Double.parseDouble(line.trim());
				dataList.add(value);
			}
		} catch (IOException | NumberFormatException e) {
			e.printStackTrace();
		}

		// Convert List<Double> to double[]
		double[] dataArray = new double[dataList.size()];
		for (int i = 0; i < dataList.size(); i++) {
			dataArray[i] = dataList.get(i);
		}

		return dataArray;
	}

	/**
	 * Convert list to array.
	 *
	 * @param listOfArrays the list of arrays
	 * @return the ilo num var[][]
	 */
	public static IloNumVar[][] convertListToArray(List<IloNumVar[]> listOfArrays) {
		int size = listOfArrays.size();
		IloNumVar[][] resultArray = new IloNumVar[size][];

		for (int i = 0; i < size; i++) {
			resultArray[i] = listOfArrays.get(i);
		}

		return resultArray;
	}

	public static double[] generateTargetTS () {
		String filePath = "src/timeSeriesDataSet/input_tsd_100-250.csv"; 
		return importTSD(filePath);
	}

	/**
	 * Gets the forecast.
	 *
	 * @param numberOfPeriod the number of period
	 * @return the forecast
	 */
	public static List<TwoStageForecast> getForecast (int numberOfPeriod) {
		Gson gson = new Gson();
		List<TwoStageForecast> listOfForecast;
		String filePath = "src/output/List_ForecastResults.json";

		try {
			// Attempt to read the forecast from the JSON file
			FileReader reader = new FileReader(filePath);
			Type forecastListType = new TypeToken<List<TwoStageForecast>>() {}.getType(); // Capture the list's type
			listOfForecast = gson.fromJson(reader, forecastListType); // Parse the JSON file
			reader.close();

		} catch (IOException e) {
			// If reading fails, generate a new forecast and save it
			System.out.println("Forecast data not found or failed to import. Generating new forecast...");
			listOfForecast = generateForecast(numberOfPeriod);

			// Serialize the new forecast to JSON
		}

		return listOfForecast; 
	}


	/**
	 * Generate forecast.
	 *
	 * @param numberOfPeriod the number of period
	 * @return the two stage forecast
	 */
	public static List<TwoStageForecast> generateForecast (int arrayLength) {
		List<TwoStageForecast> forecast = RenewableEnergyForecast.runForecasting(arrayLength);
		return forecast; 
	}

	public static OptimizationResults cloneOptimizationResult(OptimizationResults result) {
		OptimizationResults clonedResult = new OptimizationResults();
		clonedResult.setTimeStamp(result.getTimeStamp());
		clonedResult.setVariableName(result.getVariableName());
		clonedResult.setOptimizationResults(new ArrayList<>(result.getOptimizationResults()));
		return clonedResult;
	}

	/**
	 * Matches and updates the historicValues list with the last optimization result of each variable from the optimizationResultsList 
	 * based on the variableName. If no matching variableName is found in historicValues, a new entry is added.
	 *
	 * @param optimizationResultsList the list of optimization results
	 * @param historicValues the existing list of historic values to be matched and updated
	 */
	public static List<OptimizationResults> updateHistoricValuesByVariableName(List<OptimizationResults> optimizationResultsList, List<OptimizationResults> historicValues) {
		for (OptimizationResults newResult : optimizationResultsList) {
			if (!newResult.getOptimizationResults().isEmpty()) {
				// Attempt to find a matching OptimizationResults in historicValues by variableName
				Optional<OptimizationResults> match = historicValues.stream()
						.filter(historicResult -> historicResult.getVariableName().equals(newResult.getVariableName()))
						.findFirst();

				if (match.isPresent()) {
					// If a match is found, update the existing historicResult with the new last optimization result
					OptimizationResults existingResult = match.get();
					List<Double> updatedResults = new ArrayList<>(existingResult.getOptimizationResults());
					updatedResults.add(newResult.getOptimizationResults().get(newResult.getOptimizationResults().size() - 1));
					existingResult.setOptimizationResults(updatedResults);
				} else {
					// If no match is found, add a new OptimizationResults object with the new data
					OptimizationResults historicResult = new OptimizationResults();
					historicResult.setTimeStamp(newResult.getTimeStamp());
					historicResult.setVariableName(newResult.getVariableName());
					List<Double> lastResult = new ArrayList<>();
					lastResult.add(newResult.getOptimizationResults().get(newResult.getOptimizationResults().size() - 1));
					historicResult.setOptimizationResults(lastResult);
					historicValues.add(historicResult);
				}
			}
		}
		return historicValues;
	}

	public static List<OptimizationResults> updateHistoricValuesWithRTO(List<OptimizationResults> RTOResults, List<OptimizationResults> historicValues, int firstStagePeriod) {
		for (OptimizationResults RTOEntry : RTOResults) {
			if (!RTOEntry.getOptimizationResults().isEmpty()) {
				final int updateIndex = firstStagePeriod;
				Double lastResult = RTOEntry.getOptimizationResults().get(RTOEntry.getOptimizationResults().size() - 1);

				Optional<OptimizationResults> match = historicValues.stream()
						.filter(historicResult -> historicResult.getVariableName().equals(RTOEntry.getVariableName()))
						.findFirst();

				if (match.isPresent()) {
					// If a match is found, update the specific position in the existing historicResult
					OptimizationResults existingResult = match.get();
					existingResult.addOptimizationResultAt(updateIndex, lastResult);
				} else {
					// If no match is found, create a new entry and fill up to the updateIndex with null or default values
					OptimizationResults newHistoricResult = new OptimizationResults();
					newHistoricResult.setVariableName(RTOEntry.getVariableName());
					newHistoricResult.setTimeStamp(RTOEntry.getTimeStamp()); // Assuming the timestamp should be copied or set appropriately
					List<Double> resultsUpToUpdateIndex = new ArrayList<>(updateIndex);
					for (int i = 0; i < updateIndex; i++) {
						resultsUpToUpdateIndex.add(null); // Or use a default value
					}
					resultsUpToUpdateIndex.add(lastResult);
					newHistoricResult.setOptimizationResults(resultsUpToUpdateIndex);
					historicValues.add(newHistoricResult);
				}
			}
		}
		return historicValues;
	}

	/**
	 * Extract string.
	 *
	 * @param input the input
	 * @return the string
	 */
	private static String extractString(String input) {
		// Find the last occurrence of '-'
		int lastIndex = input.lastIndexOf('-');

		// If '-' is found, extract the substring from the start to the position of the last '-'
		if (lastIndex != -1) {
			return input.substring(0, lastIndex);
		}

		// If '-' is not found, return the input as it is
		return input;
	}

	/**
	 * Extract number.
	 *
	 * @param input the input
	 * @return the int
	 */
	private static int extractNumber(String input) {
		// Find the last occurrence of '-'
		int lastIndex = input.lastIndexOf('-');

		// If '-' is found, extract the substring after the last '-'
		if (lastIndex != -1) {
			String numberStr = input.substring(lastIndex + 1);
			try {
				// Attempt to convert the extracted substring to an integer
				return Integer.parseInt(numberStr);
			} catch (NumberFormatException e) {
				// If the conversion fails, log an error or handle it as needed
				System.err.println("Error converting extracted part to an integer: " + e.getMessage());
				// Optionally, return a default value or rethrow the exception
			}
		}

		// If '-' is not found or conversion fails, indicate with a special value or throw an exception
		return -1; // Using -1 to indicate failure here, adjust as needed
	}
}
