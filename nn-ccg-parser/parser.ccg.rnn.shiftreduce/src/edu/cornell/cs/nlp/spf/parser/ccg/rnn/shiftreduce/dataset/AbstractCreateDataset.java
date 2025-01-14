package edu.cornell.cs.nlp.spf.parser.ccg.rnn.shiftreduce.dataset;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.function.Predicate;

import edu.cornell.cs.nlp.spf.ccg.categories.Category;
import edu.cornell.cs.nlp.spf.ccg.lexicon.CompositeImmutableLexicon;
import edu.cornell.cs.nlp.spf.ccg.lexicon.ILexiconImmutable;
import edu.cornell.cs.nlp.spf.ccg.lexicon.LexicalEntry;
import edu.cornell.cs.nlp.spf.data.IDataItem;
import edu.cornell.cs.nlp.spf.data.ILabeledDataItem;
import edu.cornell.cs.nlp.spf.data.collection.IDataCollection;
import edu.cornell.cs.nlp.spf.data.sentence.Sentence;
import edu.cornell.cs.nlp.spf.data.singlesentence.SingleSentence;
import edu.cornell.cs.nlp.spf.data.situated.sentence.SituatedSentence;
import edu.cornell.cs.nlp.spf.data.utils.IValidator;
import edu.cornell.cs.nlp.spf.mr.lambda.LogicLanguageServices;
import edu.cornell.cs.nlp.spf.mr.lambda.LogicalExpression;
import edu.cornell.cs.nlp.spf.parser.ParsingOp;
import edu.cornell.cs.nlp.spf.parser.ccg.IParseStep;
import edu.cornell.cs.nlp.spf.parser.ccg.cky.single.CKYParser;
import edu.cornell.cs.nlp.spf.parser.ccg.model.IDataItemModel;
import edu.cornell.cs.nlp.spf.parser.ccg.model.Model;
import edu.cornell.cs.nlp.spf.parser.ccg.rnn.shiftreduce.categoryembeddings.CategoryEmbedding;
import edu.cornell.cs.nlp.spf.parser.ccg.rnn.shiftreduce.categoryembeddings.LogicalExpressionEmbedding;
import edu.cornell.cs.nlp.spf.parser.ccg.rnn.shiftreduce.embeddings.EmbedWordBuffer;
import edu.cornell.cs.nlp.spf.parser.ccg.rnn.shiftreduce.neuralnetworkparser.AbstractNeuralShiftReduceParser;
import edu.cornell.cs.nlp.spf.parser.ccg.rnn.shiftreduce.neuralnetworkparser.NeuralNetworkShiftReduceParser;
import edu.cornell.cs.nlp.spf.parser.ccg.shiftreduce.ShiftReduceDerivation;
import edu.cornell.cs.nlp.spf.parser.ccg.shiftreduce.ShiftReduceParserOutput;
import edu.cornell.cs.nlp.spf.parser.ccg.shiftreduce.stacks.DerivationState;
import edu.cornell.cs.nlp.spf.parser.ccg.shiftreduce.stacks.LexicalParsingOp;
import edu.cornell.cs.nlp.spf.parser.ccg.shiftreduce.steps.ShiftReduceLexicalStep;
import edu.cornell.cs.nlp.spf.parser.ccg.shiftreduce.steps.ShiftReduceParseStep;
import edu.cornell.cs.nlp.spf.parser.filter.IParsingFilterFactory;
import edu.cornell.cs.nlp.spf.parser.joint.IJointInferenceFilter;
import edu.cornell.cs.nlp.spf.parser.joint.IJointInferenceFilterFactory;
import edu.cornell.cs.nlp.spf.parser.joint.model.IJointDataItemModel;
import edu.cornell.cs.nlp.spf.parser.joint.model.IJointModelImmutable;
import edu.cornell.cs.nlp.utils.log.ILogger;
import edu.cornell.cs.nlp.utils.log.LogLevel;
import edu.cornell.cs.nlp.utils.log.LoggerFactory;
import edu.uw.cs.lil.amr.data.AMRMeta;
import edu.uw.cs.lil.amr.data.LabeledAmrSentence;
import edu.uw.cs.lil.amr.lambda.AMRServices;
import edu.uw.cs.lil.amr.parser.AmrParsingFilter;
import edu.uw.cs.lil.amr.parser.GraphAmrParser;

/** Dataset creation for training Neural CCG Semantic Parser 
 * @author Dipendra Misra (dkm@cs.cornell.edu)
 * */
public abstract class AbstractCreateDataset<Dataset, SAMPLE extends IDataItem<?>, DI extends ILabeledDataItem<SAMPLE, ?>, MR> {
	
	/** TODO in this file check if a lexical entry is getting added multiple times. The temp lexicon and model lexical
	 * are getting mixed while parsing. */
	public static final ILogger	LOG = LoggerFactory.create(CreateDecisionDataset.class);

	private final IDataCollection<DI> trainingData;
	private final NeuralNetworkShiftReduceParser<Sentence, MR> neuralParser;
	private final AbstractNeuralShiftReduceParser<Sentence, LogicalExpression> baseNeuralAmrParser;
	
	private final Integer beamSize;
	
	private final CKYParsingFilterFactory<SAMPLE, DI, MR> ckyParsingFilterFactory;
	private final AMRParsingFilterFactory<SAMPLE, DI> amrParsingFilterFactory;
	
	private final AmrParsingFilter validAmrParsingFilter; 
	
	/////////TEMPORARY ADDED FOR HANDLING NEW FEATURES ///////////
	public IJointModelImmutable<SituatedSentence<AMRMeta>, 
									LogicalExpression, LogicalExpression> modelNewFeatures;
	/////////////////////////////////////////////////////////////
	
	/** Length of the maximum sentence that is accepted for training. */
	private final int maxSentenceLength;
	
	/** Save the dataset. Note that saving the dataset can take huge amount of disk space. */
	private final boolean saveCreatedDataset;
	
	/** Bootstrap saved data*/
	private final boolean bootstrapDataset;
	
	/** Filter factory for early updates*/
	private final AbstractAMREarlyUpdateFilterFactory amrEarlyUpdateFilterFactory;
	
	private List<Dataset> dataset;
	
	private boolean isMemoized;
	private final List<Predicate<ParsingOp<LogicalExpression>>> memoizedFilters;
	
	private boolean useStoredGoldParseTrees;
	private final List<List<ParsingOp<LogicalExpression>>> parseTrees;
	
	private IJointInferenceFilterFactory<ILabeledDataItem<SituatedSentence<AMRMeta>, 
				LogicalExpression>, LogicalExpression, LogicalExpression, LogicalExpression> amrSupervisedFilterFactory;

	/** Constructor for neural shift reduce parser */
	public AbstractCreateDataset(IDataCollection<DI> trainingData, 
			NeuralNetworkShiftReduceParser<Sentence, MR> parser, IValidator<DI,MR> validator, Integer beamSize, 
			IParsingFilterFactory<DI, MR> parsingFilterFactory, 
			CompositeImmutableLexicon<MR> compositeLexicon, ILexiconImmutable<MR> tempLexicon, 
			CKYParser<Sentence, MR> ckyOracleParser) {
		this.trainingData = trainingData;
		this.neuralParser = parser;
		this.baseNeuralAmrParser = null;
		
		this.beamSize = beamSize;
		
		this.maxSentenceLength = -1;
		
		this.saveCreatedDataset = false;
		this.bootstrapDataset = false;
		
		this.amrEarlyUpdateFilterFactory = null;
		this.dataset = null;
		
		this.isMemoized = false;
		this.memoizedFilters = null;
		
		this.ckyParsingFilterFactory = new CKYParsingFilterFactory<SAMPLE, DI, MR>(ckyOracleParser, parsingFilterFactory);
		this.amrParsingFilterFactory = null;
		this.validAmrParsingFilter = null;
		
		this.useStoredGoldParseTrees = false;
		this.parseTrees = null;
		
		LOG.setCustomLevel(LogLevel.INFO);
	}
	
	/** Constructor for neural shift reduce AMR parser */
	public AbstractCreateDataset(IDataCollection<DI> trainingData, 
			AbstractNeuralShiftReduceParser<Sentence, LogicalExpression> baseNeuralAmrParser,
			IValidator<DI,MR> validator, Integer beamSize, IParsingFilterFactory<DI, MR> parsingFilterFactory, 
			CompositeImmutableLexicon<MR> compositeLexicon, ILexiconImmutable<MR> tempLexicon, 
			GraphAmrParser amrOracleParser, 
			IJointInferenceFilterFactory<DI, LogicalExpression, LogicalExpression, LogicalExpression> amrSupervisedFilterFactory) {
		this.trainingData = trainingData;
		this.neuralParser = null;
		this.baseNeuralAmrParser = baseNeuralAmrParser;
		this.beamSize = 2;//30;//500;//beamSize;
		
		this.maxSentenceLength = 35;//35;//Integer.MAX_VALUE;//15;//10;
		
		this.saveCreatedDataset = false;
		this.bootstrapDataset = true;
		
		this.amrEarlyUpdateFilterFactory = new AMRDiscontiguousEarlyUpdateParsingFilterFactory();
				//new AMRConservativeEarlyUpdateParsingFilterFactory();//0.5;//0.85);
		
		this.dataset = null;
		
		this.isMemoized = false;
		this.memoizedFilters = new LinkedList<Predicate<ParsingOp<LogicalExpression>>>(); 
		
		this.ckyParsingFilterFactory = null;
		this.amrSupervisedFilterFactory = (IJointInferenceFilterFactory<ILabeledDataItem<SituatedSentence<AMRMeta>,
								LogicalExpression>, LogicalExpression, LogicalExpression, LogicalExpression>) amrSupervisedFilterFactory;
		
		this.amrParsingFilterFactory = new AMRParsingFilterFactory<SAMPLE, DI>(
				amrOracleParser, (IParsingFilterFactory<DI, LogicalExpression>)parsingFilterFactory, 
					(IJointInferenceFilterFactory<ILabeledDataItem<SituatedSentence<AMRMeta>, LogicalExpression>, LogicalExpression, 
							LogicalExpression, LogicalExpression>) amrSupervisedFilterFactory);
		
		this.validAmrParsingFilter = new AmrParsingFilter();
		
		this.useStoredGoldParseTrees = false;
		this.parseTrees = new ArrayList<List<ParsingOp<LogicalExpression>>>();
		
		LOG.setCustomLevel(LogLevel.INFO);
	}
	
	/** creates pre-processed datapoints from the given sentence and parseTree*/
	protected abstract List<Dataset> preProcessDataPoints(Sentence sentence, DerivationState<MR> parseTree);
	
	/** creates pre-processed datapoints from the given situated sentence and parseTree*/
	protected abstract List<Dataset> preProcessDataPoints(SituatedSentence<AMRMeta> situatedSentence, 
																			 DerivationState<MR> parseTree);

	protected void setDatasetCreateFilter(Predicate<ParsingOp<LogicalExpression>> datasetCreateFilter) {
		this.baseNeuralAmrParser.setDatasetCreatorFilter(datasetCreateFilter);
	}
	
	@SuppressWarnings("unchecked")
	private List<Predicate<ParsingOp<LogicalExpression>>> bootstrapStoredFilters(String fileName) {
		
		final List<Predicate<ParsingOp<LogicalExpression>>> savedFilters;
		try (
			    InputStream file = new FileInputStream(fileName);
			    InputStream buffer = new BufferedInputStream(file);
			    ObjectInput input = new ObjectInputStream (buffer);
			) {
				savedFilters = (List<Predicate<ParsingOp<LogicalExpression>>>)input.readObject();
			} catch(Exception e) {
				throw new RuntimeException("Could not deserialize AMR parsing filter. Error: " + e);
			}
		
		return savedFilters;
	}
	
	private  List<Predicate<ParsingOp<LogicalExpression>>> bootstrapStoredFilters(String fileName1, String fileName2) {
		
		List<Predicate<ParsingOp<LogicalExpression>>> firstHalf = this.bootstrapStoredFilters(fileName1);
		List<Predicate<ParsingOp<LogicalExpression>>> sndHalf = this.bootstrapStoredFilters(fileName2);
		firstHalf.addAll(sndHalf);
		
		return firstHalf;
	}
	
	public void saveEarlyUpdateFilter(List<Predicate<ParsingOp<LogicalExpression>>> filters) {
		
		try (
			      OutputStream file = new FileOutputStream("./filter_conservative_type2_early_update.ser");
			      OutputStream buffer = new BufferedOutputStream(file);
			      ObjectOutput output = new ObjectOutputStream(buffer);
			) {
				  LOG.info("Saved easy update filter %s", filters.size());
			      output.writeObject(filters);
			} catch(IOException e) {
			      throw new RuntimeException("Dataset Filters could not be saved. Exception " + e);
			}
	}
	
	/** Creates data for non situated model */
	@SuppressWarnings("unchecked")
	public List<Dataset> createDataset(Model<SAMPLE, MR> model) {
		
		final CategoryEmbedding<MR> categEmbedding = this.neuralParser.getCategoryEmbedding();
		categEmbedding.induceCategoricalVectors((IDataCollection<SingleSentence>)this.trainingData);		
		
		//Do training
		LOG.info("Training Statistics");
		LOG.info("Beam Size: %s ", this.beamSize);
		LOG.info("Size of Lexicon: %s", model.getLexicon().size());
		
		long totalParsingTime = 0;
		int parsed = 0;
		
		//Initialize lexical entry embedding using the lexicon in the model
		this.neuralParser.getEmbedParsingOp().induceLexicalEntryEmbedding(model.getLexicon()); 
					
		//Step 1: Parse with current network parameters
		List<Dataset> processedDataSet =  new LinkedList<Dataset>();
		
		int ex = 0;
		
		for (final DI dataItem : this.trainingData) {
			LOG.info("=========================");
			LOG.info("Utterance: %s", dataItem.getSample());
			LOG.info("Meaning Representation: %s", dataItem.getLabel());
			
			//parse with the current model 
			final SAMPLE dataItemSample = dataItem.getSample();
			final IDataItemModel<MR> dataItemModel = model.createDataItemModel(dataItemSample);
			
			Sentence dataItemSentence = null;
			try {
				 dataItemSentence = (Sentence)dataItemSample;
			} catch(Exception e) {
				new RuntimeException("Error "+e.toString());
			}
			
			final Predicate<ParsingOp<MR>> pruningFilter =
					this.ckyParsingFilterFactory.create(dataItem, dataItemModel);
							//this.parsingFilterFactory.create(dataItem);
			
			ShiftReduceParserOutput<MR> output = (ShiftReduceParserOutput<MR>)
								this.neuralParser.parse(dataItemSentence, pruningFilter, 
											dataItemModel, true, model.getLexicon(), this.beamSize); 
			
			List<ShiftReduceDerivation<MR>> derivations = output.getBestDerivations();
			
			LOG.info("Parsing time: %s", output.getParsingTime());
			totalParsingTime = totalParsingTime + output.getParsingTime();
			
			//check if correct logical form was derived
			ShiftReduceDerivation<MR> correct = null;
			for(ShiftReduceDerivation<MR> derivation: derivations) {
				LOG.info("Derived logical form %s", derivation);
				//should also apply complete parser filter in future
				if(derivation.getCategory().getSemantics().equals(dataItem.getLabel())) {
					correct = derivation;
					break;
				}
			}
			
			ex++;
			
			if(correct == null) { //failed to parse
				LOG.info("Failed to parse the utterance");
				continue;
			}
			
			parsed++;
			LOG.info("successfully parsed the utterance");
			List<DerivationState<MR>> parseTrees = correct.getMaxScoringDerivationStates();
			
			/* generally there should be very few max scoring parse trees,
			 * since either pre-training is done or weights are randomly perturbed.
			 * Hence there will be fewer ties. */
			
			final int numParseTreePerDatapoint = 1;
			
			ListIterator<DerivationState<MR>> it = parseTrees.listIterator();
			while(it.hasNext()){
				
				if(it.nextIndex() == numParseTreePerDatapoint)
					break;
				
				DerivationState<MR> parseTree = it.next(); //use it to create preProcessedData
				
					List<Dataset> preProcessedDataSetSample =  
												this.preProcessDataPoints(dataItemSentence, parseTree);
					processedDataSet.addAll(preProcessedDataSetSample);
			}
		}

		LOG.info("DataSet size %s", processedDataSet.size());
		LOG.info("Num parsed %s / %s", parsed, ex);
		LOG.info("Total Parsing Time %s", totalParsingTime);
		LOG.info("Average Parsing Time %s", totalParsingTime/Math.max((double)ex, 1));
		
		this.dataset = processedDataSet;
				
		return processedDataSet;
	}
	
	/** Creates data for situated model */
	@SuppressWarnings("unchecked")
	public List<Dataset> createDataset(IJointModelImmutable<SituatedSentence<AMRMeta>, 
											LogicalExpression, LogicalExpression> model) {
		
		LOG.info("Exact Data Creator Statistics");
		LOG.info("Size of Raw dataset %s", this.trainingData.size());
		LOG.info("Data Creator Beam Size: %s ", this.beamSize);
		LOG.info("Size of Lexicon: %s", model.getLexicon().size());
		
		this.baseNeuralAmrParser.disablePacking();
				
		long totalParsingTime = 0;
		int parsed = 0;
					
		List<Dataset> processedDataSet =  new LinkedList<Dataset>();
		
		//Saved filters
		final List<Predicate<ParsingOp<LogicalExpression>>> savedFilters;
		
		if(this.bootstrapDataset) {
			savedFilters = this.bootstrapStoredFilters("./dataset_filters_3200.ser", "dataset_filters_3200_end.ser");
			LOG.info("Loaded %s exact filters.", savedFilters.size());			
		} else {
			savedFilters = null;
		}
		
		//Store filters for saving later on
		List<Predicate<ParsingOp<LogicalExpression>>> storedFilters = 
											new LinkedList<Predicate<ParsingOp<LogicalExpression>>>();
		
		int ex = 0;
		for (final DI dataItem : this.trainingData) {
			
			final SituatedSentence<AMRMeta> situatedSentence = (SituatedSentence<AMRMeta>) dataItem.getSample();
			final Sentence sentence = situatedSentence.getSample(); 
			
			// filter the dataItem based on length
			if(this.maxSentenceLength >=0 && sentence.getTokens().size() > this.maxSentenceLength) {
				LOG.warn("Sentence exceeding maximum sentence limit");
				continue;
			}
			
			ex++;
			
			LOG.info("=========================");
			LOG.info("Utterance: %s", sentence);
			LOG.info("Meaning Representation: %s", dataItem.getLabel());
			
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemModel = 
														model.createJointDataItemModel(situatedSentence);
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemNewFeaturesModel = 
													this.modelNewFeatures.createJointDataItemModel(situatedSentence);

			Predicate<ParsingOp<LogicalExpression>> pruningFilter;
			if(this.bootstrapDataset) {
				pruningFilter = savedFilters.get(ex - 1);
			} else {
				if(this.isMemoized) {
					pruningFilter = this.memoizedFilters.get(ex - 1);
				} else {
					Predicate<ParsingOp<LogicalExpression>> pruningFilter_ = this.amrParsingFilterFactory.create(dataItem, dataItemModel);
					
					if(pruningFilter_ == null && this.amrEarlyUpdateFilterFactory != null) { //Create early update filter
						pruningFilter = this.amrEarlyUpdateFilterFactory.createFilter(this.amrParsingFilterFactory.getChart(),
																					 (LabeledAmrSentence)dataItem);
					} else {
						pruningFilter = pruningFilter_;
					}
					
					this.memoizedFilters.add(pruningFilter);
				}
			}
			
			storedFilters.add(pruningFilter);
			
			//TODO: remove this flag
			if(this.saveCreatedDataset) {
				continue;
			}
			
			if(pruningFilter == null) {
				LOG.info("Null exact filter. skipping");
				continue;
			}
			
			//TODO remove this
			this.setDatasetCreateFilter(pruningFilter);
			
			//Clear the oracle filter. This needs to be done for filters which keep track of state
			((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).clearCursor();	
			
			//Parse with the help of oracle parse			
			ShiftReduceParserOutput<LogicalExpression> output = (ShiftReduceParserOutput<LogicalExpression>)
																	this.baseNeuralAmrParser.parse(sentence, this.validAmrParsingFilter, 
																	    dataItemNewFeaturesModel, true, null
																	    	/*model.getLexicon()*/, this.beamSize); 
			
			((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).clearCursor();	
			
			List<ShiftReduceDerivation<LogicalExpression>> derivations = output.getAllDerivations();
			
			LOG.info("Data Creation Neural Parser Parsing time: %s ms", output.getParsingTime());
			totalParsingTime = totalParsingTime + output.getParsingTime();
			
			//check if correct logical form was derived
			ShiftReduceDerivation<LogicalExpression> correct = null;
			final Category<LogicalExpression> underspecifiedAmrFilterCategory;
			
			underspecifiedAmrFilterCategory = 
							((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).getCategory();
			
			if(underspecifiedAmrFilterCategory != null) {
				
				for(ShiftReduceDerivation<LogicalExpression> derivation: derivations) {
					if(underspecifiedAmrFilterCategory.equals(derivation.getCategory())) {
						correct = derivation;
						break;
					}
				}
				
				int numCorrect = 0;
				for(ShiftReduceDerivation<LogicalExpression> derivation: derivations) {
					if(underspecifiedAmrFilterCategory.equals(derivation.getCategory())) {
						numCorrect++;
					}
				}
				
				LOG.info("Shift Reduce Derivations %s. Number Correct %s", derivations.size(), numCorrect);
				
				if(correct == null) {
					LOG.info("CKY can parser a sentence that SR cannot with AMR Filter. This is a bug. Exiting");
				}
			}
			
			if(correct == null) { //failed to parse
				LOG.info("Failed to parse the utterance");
				continue;
			}
			
			parsed++;
			LOG.info("successfully parsed the utterance");
						
			List<DerivationState<LogicalExpression>> parseTrees = correct.getMaxScoringDerivationStates();
			
			// generally there should be very few max scoring parse trees,
			// since either pre-training is done or weights are randomly perturbed.
			// Hence there will be fewer ties.
			final int numParseTreePerDatapoint = 1;
			
			LOG.info("Shift Reduce %s: Number of highest scoring parse trees %s. Number of parse Trees %s / Filter Size %s", 
											ex, parseTrees.size(), correct.numParses(), 
											((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).numParseTrees());
			
			ListIterator<DerivationState<LogicalExpression>> it = parseTrees.listIterator();
			while(it.hasNext()) {
				
				if(it.nextIndex() == numParseTreePerDatapoint)
					break;
				
				DerivationState<LogicalExpression> parseTree = it.next(); //use it to create preProcessedData
				
				List<Dataset> preProcessedDataSetSample =  
								this.preProcessDataPoints(situatedSentence, (DerivationState<MR>) parseTree);
				LOG.info("Generated % decision points", preProcessedDataSetSample.size());
				processedDataSet.addAll(preProcessedDataSetSample);
			}
		}
		this.dataset = processedDataSet;
		
		// Save the dataset. Since the entire dataset takes lots of space, 
		// therefore we only store the AMR filters.
		//TODO move the code below for saving/loading to separate function
		if(this.saveCreatedDataset) {		
			try (
				      OutputStream file = new FileOutputStream("./dataset_filters_noaugment_0-3200.ser");
				      OutputStream buffer = new BufferedOutputStream(file);
				      ObjectOutput output = new ObjectOutputStream(buffer);
				) {
					  LOG.info("Saved filter size %s", storedFilters.size());
				      output.writeObject(storedFilters);
				      System.exit(0);
				} catch(IOException e) {
				      throw new RuntimeException("Dataset Filters could not be saved. Exception " + e);
				}
		}
		
		this.setDatasetCreateFilter(null);
		
		this.isMemoized = true;
		this.baseNeuralAmrParser.enablePacking();
		
		LOG.info("Composite Dataset size %s", processedDataSet.size());
		LOG.info("Num parsed %s / %s", parsed, ex);
		LOG.info("Total Parsing Time %s", totalParsingTime);
		LOG.info("Average Parsing Time %s", totalParsingTime/Math.max((double)ex, 1));
		
		return processedDataSet;
	}
	
	/** Creates data for situated model */
	@SuppressWarnings("unchecked")
	public List<Dataset> createEarlyUpdateDataset(IJointModelImmutable<SituatedSentence<AMRMeta>, 
													LogicalExpression, LogicalExpression> model) {
		
		LOG.info("Early Update Data Creator Statistics");
		LOG.info("Size of Raw dataset %s", this.trainingData.size());
		LOG.info("Data Creator Beam Size: %s ", this.beamSize);
		LOG.info("Size of Lexicon: %s", model.getLexicon().size());
		
		this.baseNeuralAmrParser.disablePacking();
		
		List<Predicate<ParsingOp<LogicalExpression>>> earlyUpdateFilters = new ArrayList<Predicate<ParsingOp<LogicalExpression>>>();
		int earlyUpdateIndex = 0;
		
		long totalParsingTime = 0;
		int parsed = 0;
					
		List<Dataset> processedDataSet =  new LinkedList<Dataset>();
		
		//Saved filters
		final List<Predicate<ParsingOp<LogicalExpression>>> savedFilters;
		
		if(this.bootstrapDataset) {
			
			savedFilters = this.bootstrapStoredFilters("./dataset_filters_3200.ser", "dataset_filters_3200_end.ser");
			LOG.info("Loaded filters. There are %s many of them.", savedFilters.size());
			
			earlyUpdateFilters = this.bootstrapStoredFilters("./filter_conservative_type2_early_update.ser");
			LOG.info("Loaded early update filters. There are %s many of them.", earlyUpdateFilters.size());
		} else {
			savedFilters = null;
		}
		
		//Store filters for saving
		List<Predicate<ParsingOp<LogicalExpression>>> storedFilters = 
											new LinkedList<Predicate<ParsingOp<LogicalExpression>>>();
		
		int ex = 0;
		for (final DI dataItem : this.trainingData) {
			
			final SituatedSentence<AMRMeta> situatedSentence = (SituatedSentence<AMRMeta>) dataItem.getSample();
			final Sentence sentence = situatedSentence.getSample(); 
			
			// filter the dataItem based on length
			if(this.maxSentenceLength >=0 && sentence.getTokens().size() > this.maxSentenceLength) {
				LOG.warn("Sentence exceeding maximum sentence limit");
				continue;
			}
			
			ex++;
		
			LOG.info("=========================");
			LOG.info("Utterance: %s", sentence);
			LOG.info("Meaning Representation: %s", dataItem.getLabel());
			
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemModel = 
														model.createJointDataItemModel(situatedSentence);
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemNewFeaturesModel = 
													this.modelNewFeatures.createJointDataItemModel(situatedSentence);

			//parse with the help of oracle parse			
			Predicate<ParsingOp<LogicalExpression>> pruningFilter;
			if(this.bootstrapDataset) {
				pruningFilter = savedFilters.get(ex - 1);
			} else {
				if(this.isMemoized) {
					pruningFilter = this.memoizedFilters.get(ex - 1);
				} else {
					Predicate<ParsingOp<LogicalExpression>> pruningFilter_ = this.amrParsingFilterFactory.create(dataItem, dataItemModel);
					
					if(pruningFilter_ == null && this.amrEarlyUpdateFilterFactory != null) { //Create early update filter
						pruningFilter = this.amrEarlyUpdateFilterFactory
								.createFilter(this.amrParsingFilterFactory.getChart(), (LabeledAmrSentence) dataItem);
					} else {
						pruningFilter = pruningFilter_;
					}
					
					this.memoizedFilters.add(pruningFilter);
				}
			}
			
			storedFilters.add(pruningFilter);
			
			if(this.saveCreatedDataset) {
				continue;
			}
			
			/////////////
			if(this.bootstrapDataset) {
				
				if(pruningFilter == null) {
					
					pruningFilter = earlyUpdateFilters.get(earlyUpdateIndex++);
			
//					this.amrParsingFilterFactory.create(dataItem, dataItemModel);
//					
//					//Create early update filter
//					Predicate<ParsingOp<LogicalExpression>> pruningFilter_ = this.amrEarlyUpdateFilterFactory
//									.createFilter(this.amrParsingFilterFactory.getChart(), (LogicalExpression)(dataItem.getLabel()));
//					
//					earlyUpdateFilters.add(pruningFilter_);
//					
//					pruningFilter = pruningFilter_;
//					continue;
				} else {
					continue;
				}
			}
			//////////
			
			if(pruningFilter == null) {
				LOG.info("Null filter. skipping");
				continue;
			}
						
			this.setDatasetCreateFilter(pruningFilter);
			
			((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).clearCursor();
			
			@SuppressWarnings("unused")
			ShiftReduceParserOutput<LogicalExpression> output = (ShiftReduceParserOutput<LogicalExpression>)
																		this.baseNeuralAmrParser.parse(sentence, this.validAmrParsingFilter, 
																				 dataItemNewFeaturesModel, true, null
																					/*model.getLexicon()*/, this.beamSize); 
			
			if(pruningFilter instanceof CKYMultiParseTreeParsingFilter && 
			   ((CKYMultiParseTreeParsingFilter<LogicalExpression>) pruningFilter).isEarlyUpdateFilter()) {
				
				Set<DerivationState<LogicalExpression>> states = 
							((CKYMultiParseTreeParsingFilter<LogicalExpression>) pruningFilter).getEarlyUpdateStates();
				LOG.info("Found %s many early update states", states.size());
				if(states.size() > 0) {
					
					DerivationState<MR> partialParseTree = (DerivationState<MR>) states.iterator().next(); //arbitrarily picking one for now
					
					List<Dataset> preProcessedDataSetSample =  
								this.preProcessDataPoints(situatedSentence, partialParseTree);
					LOG.info("Generated % decision points", preProcessedDataSetSample.size());
					processedDataSet.addAll(preProcessedDataSetSample);
				}
				
				((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).clearCursor();	
			}
		}
		
		this.dataset = processedDataSet;
		
		// Save the dataset. Since the entire dataset takes lots of space, 
		// therefore we only store the AMR filters.
		if(this.saveCreatedDataset) {		
			try (
				      OutputStream file = new FileOutputStream("./dataset_filters_3200_end.ser");
				      OutputStream buffer = new BufferedOutputStream(file);
				      ObjectOutput output = new ObjectOutputStream(buffer);
				) {
					  LOG.info("Saved filter size %s", storedFilters.size());
				      output.writeObject(storedFilters);
				} catch(IOException e) {
				      throw new RuntimeException("Dataset Filters could not be saved. Exception " + e);
				}
		}
		
		this.setDatasetCreateFilter(null);
		
		this.isMemoized = true;
		this.baseNeuralAmrParser.enablePacking();
		
		LOG.info("Early Update Dataset size %s", processedDataSet.size());
		LOG.info("Num parsed %s / %s", parsed, ex);
		LOG.info("Total Parsing Time %s", totalParsingTime);
		LOG.info("Average Parsing Time %s", totalParsingTime/Math.max((double)ex, 1));
		
		return processedDataSet;
	}
	
	/** Creates data for situated model */
	@SuppressWarnings("unchecked")
	public List<Dataset> createDiscontiguousEarlyUpdateDataset(IJointModelImmutable<SituatedSentence<AMRMeta>, 
													LogicalExpression, LogicalExpression> model) {
		
		if(!(this.amrEarlyUpdateFilterFactory instanceof AMRDiscontiguousEarlyUpdateParsingFilterFactory)) {
			throw new RuntimeException("Cannot create discontiguous early update without a discontiguous filter");
		}
		
		LOG.info("Discontiguous Early Update Data Creator Statistics");
		LOG.info("Size of Raw dataset %s", this.trainingData.size());
		LOG.info("Data Creator Beam Size: %s ", this.beamSize);
		LOG.info("Size of Lexicon: %s", model.getLexicon().size());
		
		this.baseNeuralAmrParser.disablePacking();
		
		List<DiscontiguousCompositeFilters> earlyUpdateFilters = new ArrayList<DiscontiguousCompositeFilters>();
		int earlyUpdateIndex = 0;
		
		long totalParsingTime = 0;
		int parsed = 0;
					
		List<Dataset> processedDataSet =  new LinkedList<Dataset>();
		
		//Saved filters
		final List<Predicate<ParsingOp<LogicalExpression>>> exactParseFilters;
		
		if(this.bootstrapDataset) {
			
			exactParseFilters = this.bootstrapStoredFilters("./dataset_filters_3200.ser", "dataset_filters_3200_end.ser");
			LOG.info("Loaded filters. There are %s many of them.", exactParseFilters.size());
			
			try (
				    InputStream file = new FileInputStream("./dataset_early_update_discontiguous.ser");
				    InputStream buffer = new BufferedInputStream(file);
				    ObjectInput input = new ObjectInputStream (buffer);
				) {
					earlyUpdateFilters = (List<DiscontiguousCompositeFilters>)input.readObject();
				} catch(Exception e) {
					throw new RuntimeException("Could not deserialize AMR parsing filter. Error: " + e);
				}
			
			LOG.info("Loaded early update filters. There are %s many of them.", earlyUpdateFilters.size());
		} else {
			exactParseFilters = null;
		}
		
		//Store filters for saving
		List<DiscontiguousCompositeFilters> storedFilters = new LinkedList<DiscontiguousCompositeFilters>();
		
		int ex = 0;
		for (final DI dataItem : this.trainingData) {
			
			final SituatedSentence<AMRMeta> situatedSentence = (SituatedSentence<AMRMeta>) dataItem.getSample();
			final Sentence sentence = situatedSentence.getSample(); 
			
			// filter the dataItem based on length
			if(this.maxSentenceLength >=0 && sentence.getTokens().size() > this.maxSentenceLength) {
				LOG.warn("Sentence exceeding maximum sentence limit");
				continue;
			}
			
			ex++;
		
			LOG.info("=========================");
			LOG.info("Utterance: %s", sentence);
			LOG.info("Meaning Representation: %s", dataItem.getLabel());
			
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemModel = 
														model.createJointDataItemModel(situatedSentence);
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemNewFeaturesModel = 
													this.modelNewFeatures.createJointDataItemModel(situatedSentence);

			//parse with the help of oracle parse			
			final Predicate<ParsingOp<LogicalExpression>> exactParseFilter;
			if(this.bootstrapDataset) {
				exactParseFilter = exactParseFilters.get(ex - 1);
			} else {
				exactParseFilter = this.amrParsingFilterFactory.create(dataItem, dataItemModel);
			}
			
			DiscontiguousCompositeFilters pruningFilter;
			if(exactParseFilter == null && this.amrEarlyUpdateFilterFactory != null) {
				pruningFilter = earlyUpdateFilters.get(earlyUpdateIndex++);
			} else {
				continue;
			}
			
			storedFilters.add(pruningFilter);
			
			if(this.saveCreatedDataset) {
				continue;
			}
			
			if(pruningFilter == null) {
				LOG.info("Null filter. skipping");
				continue;
			}
			
			List<Predicate<ParsingOp<LogicalExpression>>> filters = pruningFilter.getFilters();
			
			for(Predicate<ParsingOp<LogicalExpression>> filter_: filters) {
						
				final CKYMultiParseTreeParsingFilter<LogicalExpression> filter = (CKYMultiParseTreeParsingFilter<LogicalExpression>)filter_;
				
				this.setDatasetCreateFilter(filter);
				
				filter.clearCursor();
				
				final int start = filter.getStart();
				final int end = filter.getEnd() - 1;
			
				filter.shiftParsingOpSpan();
				
				LOG.debug("Sentence %s \n Filter %s", sentence.getTokens(), filter);
				LOG.debug("Early update %s - %s with sentence %s", start, end, sentence.getTokens().sub(start, end + 1));
				
				@SuppressWarnings("unused")
				ShiftReduceParserOutput<LogicalExpression> output = (ShiftReduceParserOutput<LogicalExpression>)
																		this.baseNeuralAmrParser.parseSubSpan(sentence, this.validAmrParsingFilter, 
																			dataItemNewFeaturesModel, true, null, this.beamSize, start, end); 
				
				if(filter instanceof CKYMultiParseTreeParsingFilter && 
				   ((CKYMultiParseTreeParsingFilter<LogicalExpression>) filter).isEarlyUpdateFilter()) {
					
					Set<DerivationState<LogicalExpression>> states = filter.getEarlyUpdateStates();
					LOG.info("Found %s many early update states", states.size());
					if(states.size() > 0) {
						
						DerivationState<MR> partialParseTree = (DerivationState<MR>) states.iterator().next(); //arbitrarily picking one for now
						
						List<Dataset> preProcessedDataSetSample =  
									this.preProcessDataPoints(situatedSentence, partialParseTree);
						LOG.info("Generated % decision points", preProcessedDataSetSample.size());
						processedDataSet.addAll(preProcessedDataSetSample);
					}
					
					filter.clearCursor();	
				}
			}
		}
		
		this.dataset = processedDataSet;
		
		// Save the dataset. Since the entire dataset takes lots of space, 
		// therefore we only store the AMR filters.
		if(this.saveCreatedDataset) {		
			try (
				      OutputStream file = new FileOutputStream("./dataset_early_update_discontiguous.ser");
				      OutputStream buffer = new BufferedOutputStream(file);
				      ObjectOutput output = new ObjectOutputStream(buffer);
				) {
				      output.writeObject(storedFilters);
				      LOG.info("Saved %s many early update discontiguous filters", earlyUpdateFilters.size());
				} catch(IOException e) {
				      throw new RuntimeException("Dataset Filters could not be saved. Exception " + e);
				}
		}
		
		this.setDatasetCreateFilter(null);
		
		this.isMemoized = true;
		this.baseNeuralAmrParser.enablePacking();
		
		LOG.info("Early Update Dataset size %s", processedDataSet.size());
		LOG.info("Num parsed %s / %s", parsed, ex);
		LOG.info("Total Parsing Time %s", totalParsingTime);
		LOG.info("Average Parsing Time %s", totalParsingTime/Math.max((double)ex, 1));
		
		return processedDataSet;
	}
	
	@SuppressWarnings("unused")
	private void printToFile(List<Predicate<ParsingOp<LogicalExpression>>> filters, Sentence sentence) {
		
		try { 
		
			PrintWriter writer = new PrintWriter(new FileWriter("early_update_filters.txt", true));
			
			writer.println(": =======");
			writer.println(sentence.getTokens().toString());
			
			for(Predicate<ParsingOp<LogicalExpression>> filter: filters) { 
				CKYMultiParseTreeParsingFilter<LogicalExpression> f = (CKYMultiParseTreeParsingFilter<LogicalExpression>) filter;
				final int start = f.getStart();
				final int end = f.getEnd();
				writer.println("Segment: " + start + "-" + end + ": " + sentence.getTokens().sub(start, end));
				List<List<ParsingOp<LogicalExpression>>> trees = f.getParseTrees();
				if(trees.size() > 0) {
					List<ParsingOp<LogicalExpression>> tree = trees.get(0);
					for(ParsingOp<LogicalExpression> op: tree) {
							writer.println(op);
					}
				} else {
					writer.println("No tree found");
				}
				writer.println();
			}
			writer.println("\n");
			writer.close();
		
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	@SuppressWarnings("unused")
	private void parserWithAMRFilter(Sentence sentence, IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemNewFeaturesModel, 
			ILabeledDataItem<SituatedSentence<AMRMeta>, LogicalExpression> dataItem) {
		
		LogicalExpression groundTruth = dataItem.getLabel();
		LogicalExpression underspecified = AMRServices.underspecifyAndStrip(groundTruth);
		
		LOG.info("Underspecified ground truth %s", underspecified);
		
		IJointInferenceFilter<LogicalExpression, LogicalExpression, LogicalExpression> amrSupervisedFilter = 
				this.amrSupervisedFilterFactory.createJointFilter(
						(ILabeledDataItem<SituatedSentence<AMRMeta>, LogicalExpression>) dataItem);
	
		//Pass the AMR filter
		this.setDatasetCreateFilter(amrSupervisedFilter);
		
		ShiftReduceParserOutput<LogicalExpression> output = (ShiftReduceParserOutput<LogicalExpression>)
									this.baseNeuralAmrParser.parse(sentence, this.validAmrParsingFilter, 
													dataItemNewFeaturesModel, true, null/*model.getLexicon()*/, this.beamSize); 
		
		List<ShiftReduceDerivation<LogicalExpression>> derivations = output.getAllDerivations();
		
		LOG.info("CKY failed: Found %s derivations", derivations.size());
		
		for(ShiftReduceDerivation<LogicalExpression> derivation: derivations) {
			Category<LogicalExpression> derivedCategory = derivation.getCategory();
			
			LogicalExpression derivedUnderspecified = AMRServices.underspecifyAndStrip(derivedCategory.getSemantics());
			
			if(derivedUnderspecified.equals(underspecified)) {
				LOG.info("CKY failed: Parsed a sentence that CKY could not parse");
				break;
			} else {
				LOG.info("Found %s %s %s %s", derivedUnderspecified, derivedUnderspecified.hashCode(), 
						underspecified.hashCode(), LogicLanguageServices.isEqual(derivedUnderspecified, underspecified));
			}
		}
	}
	
	/** Creates data for situated model */
	@SuppressWarnings("unchecked")
	public void catchEarlyErrorParser(IDataCollection<LabeledAmrSentence> data, IJointModelImmutable<SituatedSentence<AMRMeta>, 
											LogicalExpression, LogicalExpression> model, Integer beamSize) {
		
		LOG.info("Catching Early Error");
		LOG.info("Size of Raw dataset %s", data.size());
		LOG.info("Data Creator Beam Size: %s ", beamSize);
		LOG.info("Size of Lexicon: %s", model.getLexicon().size());
		
		this.baseNeuralAmrParser.disablePacking();
		
		long totalParsingTime = 0;
		
		for (final LabeledAmrSentence dataItem : data) {
			
			final SituatedSentence<AMRMeta> situatedSentence = (SituatedSentence<AMRMeta>) dataItem.getSample();
			final Sentence sentence = situatedSentence.getSample(); 
			
			// filter the dataItem based on length
			if(this.maxSentenceLength >=0 && sentence.getTokens().size() > this.maxSentenceLength) {
				LOG.warn("Sentence exceeding maximum sentence limit");
				continue;
			}
			
			LOG.info("=========================");
			LOG.info("Utterance: %s", sentence);
			LOG.info("Meaning Representation: %s", dataItem.getLabel());
			
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemModel = 
														model.createJointDataItemModel(situatedSentence);
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemNewFeaturesModel = 
														modelNewFeatures.createJointDataItemModel(situatedSentence);

			//parse with the help of oracle parse			
			final Predicate<ParsingOp<LogicalExpression>> pruningFilter
								= this.amrParsingFilterFactory.create((DI) dataItem, dataItemModel);
						
			if(pruningFilter == null) {
				LOG.info("null filter. skipping");
				continue;
			}
			
			this.setDatasetCreateFilter(pruningFilter);
			
			ShiftReduceParserOutput<LogicalExpression> output = (ShiftReduceParserOutput<LogicalExpression>)
																		this.baseNeuralAmrParser.parserCatchEarlyErrors(sentence, 
																				this.validAmrParsingFilter, dataItemNewFeaturesModel, true, null
																					/*model.getLexicon()*/, beamSize); 
			if(output == null) {
				LOG.info("Caught early error. Skipping.");
				continue;
			}
			
			List<ShiftReduceDerivation<LogicalExpression>> derivations = output.getAllDerivations();
			
			LOG.info("Parsing time: %s", output.getParsingTime());
			totalParsingTime = totalParsingTime + output.getParsingTime();
			
			//check if correct logical form was derived
			ShiftReduceDerivation<LogicalExpression> correct = null;
			final Category<LogicalExpression> underspecifiedAmrFilterCategory;
			
			underspecifiedAmrFilterCategory = 
							((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).getCategory();
			
			if(underspecifiedAmrFilterCategory == null) {
				correct = null;
			} else {
				
				for(ShiftReduceDerivation<LogicalExpression> derivation: derivations) {
					//LOG.info("Derived logical form %s", derivation);
					if(underspecifiedAmrFilterCategory.equals(derivation.getCategory())) {
						correct = derivation;
						break;
					}
				}
				
				int numCorrect = 0;
				for(ShiftReduceDerivation<LogicalExpression> derivation: derivations) {
					if(underspecifiedAmrFilterCategory.equals(derivation.getCategory())) {
						numCorrect++;
					}
				}
				
				LOG.info("Shift Reduce Derivations %s. Number Correct %s", derivations.size(), numCorrect);
				
				////////////////////// debug /////////////////////
				if(correct == null) {
					LOG.info("SR failed to parser though there was no early error. This is strange.");
				}
				/////////////////////////////////////////////////
			}
			
			if(correct == null) { //failed to parse
				LOG.info("Failed to parse the utterance");
				continue;
			}
		}

		this.setDatasetCreateFilter(null);
		this.baseNeuralAmrParser.enablePacking();
	}
	
	/** Performs perceptron update */
	@SuppressWarnings("unchecked")
	public void doPerceptronUpdate(IJointModelImmutable<SituatedSentence<AMRMeta>, 
											LogicalExpression, LogicalExpression> model, int perceptronUpdateBeamSize) {
		
		LOG.info("Perceptron update");
		LOG.info("Size of Raw dataset %s", this.trainingData.size());
		LOG.info("Data Creator Beam Size: %s, Perceptron update size %s ", this.beamSize, perceptronUpdateBeamSize);
		LOG.info("Size of Lexicon: %s", model.getLexicon().size());
		
		this.baseNeuralAmrParser.disablePacking();
				
		long totalParsingTime = 0;
		int parsed = 0;
		
		//Saved filters
		final List<Predicate<ParsingOp<LogicalExpression>>> savedFilters;
		
		if(this.bootstrapDataset) {
			savedFilters = this.bootstrapStoredFilters("./dataset_filters_3200.ser", "dataset_filters_3200_end.ser");
			LOG.info("Loaded %s exact filters.", savedFilters.size());			
		} else {
			throw new RuntimeException("Call this function with bootstrapped filters");
		}
			
		int ex = 0;
		int nextree = 0;
		double averageBreakAt = 0;
		
		for (final DI dataItem : this.trainingData) {
			
			final SituatedSentence<AMRMeta> situatedSentence = (SituatedSentence<AMRMeta>) dataItem.getSample();
			final Sentence sentence = situatedSentence.getSample(); 
			
			// filter the dataItem based on length
			if(this.maxSentenceLength >=0 && sentence.getTokens().size() > this.maxSentenceLength) {
				LOG.warn("Sentence exceeding maximum sentence limit");
				continue;
			}
			
			ex++;
			
			LOG.info("=========================");
			LOG.info("Utterance: %s", sentence);
			LOG.info("Meaning Representation: %s", dataItem.getLabel());
			
			final IJointDataItemModel<LogicalExpression, LogicalExpression> dataItemNewFeaturesModel = 
													this.modelNewFeatures.createJointDataItemModel(situatedSentence);

			Predicate<ParsingOp<LogicalExpression>> pruningFilter = savedFilters.get(ex - 1);
			
			if(pruningFilter == null) {
				LOG.info("Null exact filter. skipping");
				continue;
			}
			
			final List<ParsingOp<LogicalExpression>> goldTree;
			
			if(this.useStoredGoldParseTrees) {
				goldTree = this.parseTrees.get(nextree++);
				if(goldTree == null) {
					LOG.info("Gold tree null.");
					continue;
				}
				parsed++;
			} else {
				
				this.setDatasetCreateFilter(pruningFilter);
				
				//Clear the oracle filter. This needs to be done for filters which keep track of state
				((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).clearCursor();	
				
				//Parse with the help of oracle parse			
				ShiftReduceParserOutput<LogicalExpression> output = (ShiftReduceParserOutput<LogicalExpression>)
																		this.baseNeuralAmrParser.parse(sentence, this.validAmrParsingFilter, 
																		    dataItemNewFeaturesModel, true, null, this.beamSize); 
				
				((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).clearCursor();	
				
				List<ShiftReduceDerivation<LogicalExpression>> derivations = output.getAllDerivations();
				
				LOG.info("Data Creation Neural Parser Parsing time: %s ms", output.getParsingTime());
				totalParsingTime = totalParsingTime + output.getParsingTime();
				
				//check if correct logical form was derived
				ShiftReduceDerivation<LogicalExpression> correct = null;
				final Category<LogicalExpression> underspecifiedAmrFilterCategory;
				
				underspecifiedAmrFilterCategory = 
								((CKYMultiParseTreeParsingFilter<LogicalExpression>)pruningFilter).getCategory();
				
				if(underspecifiedAmrFilterCategory != null) {
					
					for(ShiftReduceDerivation<LogicalExpression> derivation: derivations) {
						if(underspecifiedAmrFilterCategory.equals(derivation.getCategory())) {
							correct = derivation;
							break;
						}
					}
					
					int numCorrect = 0;
					for(ShiftReduceDerivation<LogicalExpression> derivation: derivations) {
						if(underspecifiedAmrFilterCategory.equals(derivation.getCategory())) {
							numCorrect++;
						}
					}
					
					LOG.info("Shift Reduce Derivations %s. Number Correct %s", derivations.size(), numCorrect);
					
					////////////////////// debug /////////////////////
					if(correct == null) {
						LOG.info("CKY can parser a sentence that SR cannot with AMR Filter. This is a bug. Exiting");
					}
					/////////////////////////////////////////////////
				}
				
				if(correct == null) { //failed to parse
					LOG.info("Failed to parse the utterance");
					this.parseTrees.add(null);
					continue;
				}
				
				parsed++;
				LOG.info("successfully parsed the utterance");
							
				List<DerivationState<LogicalExpression>> parseTrees = correct.getMaxScoringDerivationStates();	
				DerivationState<LogicalExpression> parseTree = parseTrees.get(0); 
				
				//Extract actions from this tree
				goldTree = parseTree.returnParsingOps();
				Collections.reverse(goldTree);
				
				//Save the parse tree
				this.parseTrees.add(goldTree);
			}
			
			for(ParsingOp<LogicalExpression> op: goldTree) {
				LOG.info("Golden op %s", op);
			}
			
			//Do perceptron update with the above gold tree
			this.setDatasetCreateFilter(null);
			
			double failAt = this.baseNeuralAmrParser.doEarlyUpdatePerceptron(sentence, this.validAmrParsingFilter, 
							dataItemNewFeaturesModel, true, null, perceptronUpdateBeamSize, goldTree); 
			double metric = failAt/(double)(goldTree.size());
			averageBreakAt = averageBreakAt + metric;
			LOG.info("Fail at %s ", metric);
		}
		
		this.setDatasetCreateFilter(null);
		this.useStoredGoldParseTrees = true;
		this.baseNeuralAmrParser.enablePacking();
		
		LOG.info("Perceptron, Average Break at %s", averageBreakAt/(double)parsed);
		LOG.info("Num parsed %s / %s", parsed, ex);
		LOG.info("Total Parsing Time %s", totalParsingTime);
		LOG.info("Average Parsing Time %s", totalParsingTime/Math.max((double)ex, 1));
	}
	
	public IJointModelImmutable<SituatedSentence<AMRMeta>, LogicalExpression, LogicalExpression> getModelNewFeatures() {
		return this.modelNewFeatures;
	}
	
	@SuppressWarnings("unchecked")
	public List<Dataset> readFromFile(String fileName) {
		
		final List<Dataset> processedDataset;
		
		LOG.info("File name is  " + fileName);
		
		try(
			InputStream file = new FileInputStream(fileName);
			InputStream buffer = new BufferedInputStream(file);
		    ObjectInput input = new ObjectInputStream (buffer);
		) {
			processedDataset = (List<Dataset>)input.readObject();
			this.dataset = processedDataset;
	    } catch(Exception e){
	    	throw new RuntimeException("Dataset could not be read. Exception " + e);
	    }
		
		return processedDataset;
	}
	
	@SuppressWarnings("unchecked")
	public void induceSemanticsVector(LogicalExpressionEmbedding logicalExpressionEmbedding) {
		logicalExpressionEmbedding.induceCategoricalVectorsFromAmr((IDataCollection<LabeledAmrSentence>) trainingData);
	}
	
	/** Given a model initializes*/
	public void datasetCreatorInit(IJointModelImmutable<SituatedSentence<AMRMeta>, 
			LogicalExpression, LogicalExpression> model) {
		
		NeuralNetworkShiftReduceParser<Sentence, LogicalExpression> neuralNetworkParser = null;
		
		if(this.baseNeuralAmrParser instanceof NeuralNetworkShiftReduceParser) {
			neuralNetworkParser = ((NeuralNetworkShiftReduceParser<Sentence, LogicalExpression>)
													this.baseNeuralAmrParser);
		}
		
		//Initialize AMR specific Syntax vectors and semantic constants.
		this.categoryAmrPreprocessing();
		
		//Initialize lexical entry embedding using the lexicon in the model
		neuralNetworkParser.getEmbedParsingOp().induceLexicalEntryEmbedding(model.getLexicon()); 
	}
	
	/** Using the training data, we want to induce constants which are used for embedding categories*/
	@SuppressWarnings("unchecked")
	public void categoryAmrPreprocessing() {
		
		NeuralNetworkShiftReduceParser<Sentence, LogicalExpression> neuralNetworkParser = null;
		
		if(this.baseNeuralAmrParser instanceof NeuralNetworkShiftReduceParser) {
			neuralNetworkParser = ((NeuralNetworkShiftReduceParser<Sentence, LogicalExpression>)
													this.baseNeuralAmrParser);
		}
		
		final CategoryEmbedding<LogicalExpression> categEmbedding = 
												neuralNetworkParser.getCategoryEmbedding();
		categEmbedding.initializeAmrSpecificSyntaxVectors();
		categEmbedding.induceCategoricalVectorsFromAmr(
							(IDataCollection<LabeledAmrSentence>)this.trainingData);
		
		final EmbedWordBuffer embedWordBuffer = neuralNetworkParser.getEmbedWordBuffer();
		embedWordBuffer.initializeWordAndPosEmbeddings(
							(IDataCollection<LabeledAmrSentence>)this.trainingData);
	}
	
	public List<Dataset> getDataset() {
		return this.dataset;
	}
	
	/** Removes duplicate parsing operations from this list. Two parsing op are duplicate
	 * if they have the same span, same rule and same category. Duplicate parsing ops can 
	 * hinder learning if one of them is considered a ground truth. In which case, we will 
	 * be maximizing the score of one of them while minimizing the other which will negate
	 * each other. */
	protected void filterDuplicateParsingOp(List<ParsingOp<MR>> parsingOps) {
		
		Set<ParsingOp<MR>> rmDupParsingOps = new LinkedHashSet<>(parsingOps);
		parsingOps.clear();
		parsingOps.addAll(rmDupParsingOps);
	}
	
	/** Compares equality between parsing op and a parse step. This function should
	 * be removed and parsing op equality should be used instead. */
	private boolean isEqual(IParseStep<MR> parseStep, ParsingOp<MR> op) {
		
		if((op.getSpan().getStart() != parseStep.getStart()) || 
		   (op.getSpan().getEnd() != parseStep.getEnd())) {
			return false;
		}
		
		if(!op.getRule().equals(parseStep.getRuleName())) {
			return false;
		}
		
		if(!op.getCategory().equals(parseStep.getRoot())) {
			return false;
		}
		
		if(parseStep instanceof ShiftReduceLexicalStep) {
			ShiftReduceLexicalStep<MR> shiftStep = (ShiftReduceLexicalStep<MR>)parseStep;
			if(op instanceof LexicalParsingOp) {
				LexicalEntry<MR> lexicalEntry = ((LexicalParsingOp<MR>)op).getEntry();
				if(!lexicalEntry.equals(shiftStep.getLexicalEntry())) {
					return false;
				}
			} else {
				return false;
			}
		} else if(parseStep instanceof ShiftReduceParseStep) {
			if(op instanceof LexicalParsingOp) {
				return false;
			} 
		} else {
			throw new RuntimeException("This class extends facility for Shift Reduce parser only");
		}
		
		return true;
	}
	
	/** Find the ground truth index from the list of possible actions. The ground
	 * truth is represented by the Shift Reduce Step */
	protected int findGroundTruthIx(List<ParsingOp<MR>> possibleActions, 
									IParseStep<MR> step) {
		
		int gTruthIx = -1, ix = -1;
		boolean found = false;
		
		Iterator<ParsingOp<MR>> possibleActionIt = possibleActions.iterator();
		while(possibleActionIt.hasNext()) {
			ix++;
			ParsingOp<MR> op = possibleActionIt.next();
			
			if(this.isEqual(step, op)) {
				if(found) {
					throw new RuntimeException("Two identical parsing operations. This should not happen");
				} else {
					gTruthIx = ix;
					found = true;
				}
			}
		}
				
		if(!found) {
			throw new IllegalStateException("Possible Actions for a state do not contain " + 
											 " the action that was used to create the state.");
		}
				
		return gTruthIx;
	}
}