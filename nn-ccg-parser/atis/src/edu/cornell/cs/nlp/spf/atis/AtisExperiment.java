/*******************************************************************************
 * Copyright (C) 2011 - 2015 Yoav Artzi, All rights reserved.
 * <p>
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *******************************************************************************/
package edu.cornell.cs.nlp.spf.atis;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.cornell.cs.nlp.spf.atis.parser.ccg.rules.typeshifting.PPComposition;
import edu.cornell.cs.nlp.spf.base.hashvector.HashVectorFactory;
import edu.cornell.cs.nlp.spf.base.hashvector.HashVectorFactory.Type;
import edu.cornell.cs.nlp.spf.ccg.categories.syntax.Syntax;
import edu.cornell.cs.nlp.spf.ccg.lexicon.ILexicon;
import edu.cornell.cs.nlp.spf.ccg.lexicon.LexicalEntry;
import edu.cornell.cs.nlp.spf.ccg.lexicon.Lexicon;
import edu.cornell.cs.nlp.spf.ccg.lexicon.LexicalEntry.Origin;
import edu.cornell.cs.nlp.spf.ccg.lexicon.factored.lambda.FactoredLexicalEntry;
import edu.cornell.cs.nlp.spf.ccg.lexicon.factored.lambda.FactoredLexicon;
import edu.cornell.cs.nlp.spf.ccg.lexicon.factored.lambda.FactoringServices;
import edu.cornell.cs.nlp.spf.data.sentence.Sentence;
import edu.cornell.cs.nlp.spf.data.singlesentence.SingleSentence;
import edu.cornell.cs.nlp.spf.explat.DistributedExperiment;
import edu.cornell.cs.nlp.spf.explat.Job;
import edu.cornell.cs.nlp.spf.learn.ILearner;
import edu.cornell.cs.nlp.spf.mr.lambda.FlexibleTypeComparator;
import edu.cornell.cs.nlp.spf.mr.lambda.LogicLanguageServices;
import edu.cornell.cs.nlp.spf.mr.lambda.LogicalConstant;
import edu.cornell.cs.nlp.spf.mr.lambda.LogicalExpression;
import edu.cornell.cs.nlp.spf.mr.lambda.ccg.LogicalExpressionCategoryServices;
import edu.cornell.cs.nlp.spf.mr.lambda.ccg.SimpleFullParseFilter;
import edu.cornell.cs.nlp.spf.mr.language.type.TypeRepository;
import edu.cornell.cs.nlp.spf.parser.ccg.cky.CKYBinaryParsingRule;
import edu.cornell.cs.nlp.spf.parser.ccg.cky.CKYUnaryParsingRule;
import edu.cornell.cs.nlp.spf.parser.ccg.cky.multi.MultiCKYParser;
import edu.cornell.cs.nlp.spf.parser.ccg.cky.sloppy.BackwardSkippingRule;
import edu.cornell.cs.nlp.spf.parser.ccg.cky.sloppy.ForwardSkippingRule;
import edu.cornell.cs.nlp.spf.parser.ccg.cky.sloppy.SimpleWordSkippingLexicalGenerator;
import edu.cornell.cs.nlp.spf.parser.ccg.model.IModelImmutable;
import edu.cornell.cs.nlp.spf.parser.ccg.model.IModelInit;
import edu.cornell.cs.nlp.spf.parser.ccg.model.Model;
import edu.cornell.cs.nlp.spf.parser.ccg.model.ModelLogger;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.lambda.PluralExistentialTypeShifting;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.lambda.ThatlessRelative;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.lambda.typeraising.ForwardTypeRaisedComposition;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.lambda.typeshifting.PrepositionTypeShifting;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.primitivebinary.application.BackwardApplication;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.primitivebinary.application.ForwardApplication;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.primitivebinary.composition.BackwardComposition;
import edu.cornell.cs.nlp.spf.parser.ccg.rules.primitivebinary.composition.ForwardComposition;
import edu.cornell.cs.nlp.spf.parser.graph.IGraphParser;
import edu.cornell.cs.nlp.spf.test.Tester;
import edu.cornell.cs.nlp.spf.test.stats.ExactMatchTestingStatistics;
import edu.cornell.cs.nlp.utils.collections.ListUtils;
import edu.cornell.cs.nlp.utils.log.ILogger;
import edu.cornell.cs.nlp.utils.log.LogLevel;
import edu.cornell.cs.nlp.utils.log.Logger;
import edu.cornell.cs.nlp.utils.log.LoggerFactory;

public class AtisExperiment extends DistributedExperiment {
	public static final ILogger						LOG	= LoggerFactory
																.create(AtisExperiment.class);

	private final LogicalExpressionCategoryServices	categoryServices;

	public AtisExperiment(File initFile) throws IOException {
		this(initFile, Collections.<String, String> emptyMap());
	}

	public AtisExperiment(File initFile, Map<String, String> envParams)
			throws IOException {
		super(initFile, envParams, new AtisResourceRepo());

		LogLevel.DEV.set();
		Logger.setSkipPrefix(true);

		// //////////////////////////////////////////
		// Get parameters
		// //////////////////////////////////////////
		final File typesFile = globalParams.getAsFile("types");
		final List<File> seedLexiconFiles = globalParams.getAsFiles("seedlex");
		final List<File> npLexiconFiles = globalParams.getAsFiles("nplist");
		final int parserBeamSize = Integer.parseInt(globalParams.get("beam"));

		// //////////////////////////////////////////
		// Use tree hash vector
		// //////////////////////////////////////////

		HashVectorFactory.DEFAULT = Type.TREE;

		// //////////////////////////////////////////
		// Init typing system
		// //////////////////////////////////////////

		try {
			// Init the logical expression type system
			LogicLanguageServices
					.setInstance(new LogicLanguageServices.Builder(
							new TypeRepository(typesFile),
							new FlexibleTypeComparator())
							.setUseOntology(true)
							.setNumeralTypeName("i")
							.addConstantsToOntology(
									globalParams.getAsFiles("ont"))
							.closeOntology(true).build());

			storeResource(ONTOLOGY_RESOURCE,
					LogicLanguageServices.getOntology());

		} catch (final IOException e) {
			throw new RuntimeException(e);
		}

		// //////////////////////////////////////////////////
		// Category services for logical expressions
		// //////////////////////////////////////////////////

		this.categoryServices = new LogicalExpressionCategoryServices(true);
		storeResource(CATEGORY_SERVICES_RESOURCE, categoryServices);

		// //////////////////////////////////////////////////
		// Lexical factoring services
		// //////////////////////////////////////////////////

		FactoringServices.set(new FactoringServices.Builder()
				.addConstant(LogicalConstant.read("exists:<<e,t>,t>"))
				.addConstant(LogicalConstant.read("the:<<e,t>,e>")).build());

		// //////////////////////////////////////////////////
		// Initial lexicon
		// //////////////////////////////////////////////////

		// Create a static set of lexical entries, which are factored using
		// non-maximal factoring (each lexical entry is factored to multiple
		// entries). This static set is used to init the model with various
		// templates and lexemes.

		final Lexicon<LogicalExpression> readLexicon = new Lexicon<LogicalExpression>();
		for (final File file : seedLexiconFiles) {
			readLexicon.addEntriesFromFile(file, categoryServices,
					Origin.FIXED_DOMAIN);
		}

		final Lexicon<LogicalExpression> semiFactored = new Lexicon<LogicalExpression>();
		for (final LexicalEntry<LogicalExpression> entry : readLexicon
				.toCollection()) {
			for (final FactoredLexicalEntry factoredEntry : FactoringServices
					.factor(entry, true, true, 2)) {
				semiFactored.add(FactoringServices.factor(factoredEntry));
			}
		}
		storeResource("seedLexicon", semiFactored);

		// Read NP list
		final ILexicon<LogicalExpression> npLexicon = new FactoredLexicon();
		for (final File file : npLexiconFiles) {
			npLexicon.addEntriesFromFile(file, categoryServices,
					Origin.FIXED_DOMAIN);
		}
		storeResource("npLexicon", npLexicon);

		// //////////////////////////////////////////////////
		// CKY Parser
		// //////////////////////////////////////////////////

		final Set<Syntax> parseRoots = new HashSet<Syntax>();
		parseRoots.add(Syntax.S);
		parseRoots.add(Syntax.N);
		parseRoots.add(Syntax.NP);
		parseRoots.add(Syntax.PP);
		final SimpleFullParseFilter filter = new SimpleFullParseFilter(
				parseRoots);

		final IGraphParser<Sentence, LogicalExpression> parser = new MultiCKYParser.Builder<Sentence, LogicalExpression>(
				categoryServices)
				.setCompleteParseFilter(filter)
				.setPruneLexicalCells(true)
				.setPreChartPruning(true)
				.addSloppyLexicalGenerator(
						new SimpleWordSkippingLexicalGenerator<Sentence, LogicalExpression>(
								categoryServices))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new ForwardComposition<LogicalExpression>(
										categoryServices, 1, false)))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new BackwardComposition<LogicalExpression>(
										categoryServices, 1, false)))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new ForwardApplication<LogicalExpression>(
										categoryServices)))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new BackwardApplication<LogicalExpression>(
										categoryServices)))
				.addParseRule(
						new ForwardSkippingRule<LogicalExpression>(
								categoryServices))
				.addParseRule(
						new BackwardSkippingRule<LogicalExpression>(
								categoryServices, false))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new ForwardTypeRaisedComposition(
										categoryServices)))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new ThatlessRelative(categoryServices)))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new PluralExistentialTypeShifting(
										categoryServices)))
				.addParseRule(
						new CKYBinaryParsingRule<LogicalExpression>(
								new PPComposition(categoryServices)))
				.addParseRule(
						new CKYUnaryParsingRule<LogicalExpression>(
								new PrepositionTypeShifting(categoryServices)))
				.setMaxNumberOfCellsInSpan(parserBeamSize).build();

		storeResource(PARSER_RESOURCE, parser);

		// //////////////////////////////////////////////////
		// Read resources
		// //////////////////////////////////////////////////

		readResrouces();

		// //////////////////////////////////////////////////
		// Create jobs
		// //////////////////////////////////////////////////

		for (final Parameters params : jobParams) {
			addJob(createJob(params));
		}

	}

	private Job createJob(Parameters params) throws FileNotFoundException {
		final String type = params.get("type");
		if (type.equals("train")) {
			return createTrainJob(params);
		} else if (type.equals("test")) {
			return createTestJob(params);
		} else if (type.equals("log")) {
			return createModelLoggingJob(params);
		} else if ("init".equals(type)) {
			return createModelInitJob(params);
		} else {
			throw new RuntimeException("Unsupported job type: " + type);
		}
	}

	private Job createModelInitJob(Parameters params)
			throws FileNotFoundException {
		final Model<Sentence, LogicalExpression> model = get(params
				.get("model"));
		final List<IModelInit<Sentence, LogicalExpression>> modelInits = ListUtils
				.map(params.getSplit("init"),
						new ListUtils.Mapper<String, IModelInit<Sentence, LogicalExpression>>() {

							@Override
							public IModelInit<Sentence, LogicalExpression> process(
									String obj) {
								return get(obj);
							}
						});

		return new Job(params.get("id"), new HashSet<String>(
				params.getSplit("dep")), this,
				createJobOutputFile(params.get("id")),
				createJobLogFile(params.get("id"))) {

			@Override
			protected void doJob() {
				for (final IModelInit<Sentence, LogicalExpression> modelInit : modelInits) {
					modelInit.init(model);
				}
			}
		};
	}

	private Job createModelLoggingJob(Parameters params)
			throws FileNotFoundException {
		final IModelImmutable<?, ?> model = get(params.get("model"));
		final ModelLogger modelLogger = get(params.get("logger"));
		return new Job(params.get("id"), new HashSet<String>(
				params.getSplit("dep")), this,
				createJobOutputFile(params.get("id")),
				createJobLogFile(params.get("id"))) {

			@Override
			protected void doJob() {
				modelLogger.log(model, getOutputStream());
			}
		};
	}

	private Job createTestJob(Parameters params) throws FileNotFoundException {

		// Make the stats
		final ExactMatchTestingStatistics<Sentence, LogicalExpression, SingleSentence> stats = new ExactMatchTestingStatistics<Sentence, LogicalExpression, SingleSentence>();

		// Get the tester
		final Tester<Sentence, LogicalExpression, SingleSentence> tester = get(params
				.get("tester"));

		// The model to use
		final Model<Sentence, LogicalExpression> model = get(params
				.get("model"));

		// Create and return the job
		return new Job(params.get("id"), new HashSet<String>(
				params.getSplit("dep")), this,
				createJobOutputFile(params.get("id")),
				createJobLogFile(params.get("id"))) {

			@Override
			protected void doJob() {

				// Record start time
				final long startTime = System.currentTimeMillis();

				// Job started
				LOG.info("============ (Job %s started)", getId());

				tester.test(model, stats);
				LOG.info("%s", stats);

				// Output total run time
				LOG.info("Total run time %.4f seconds",
						(System.currentTimeMillis() - startTime) / 1000.0);

				// Job completed
				LOG.info("============ (Job %s completed)", getId());
			}
		};
	}

	@SuppressWarnings("unchecked")
	private Job createTrainJob(Parameters params) throws FileNotFoundException {
		// The model to use
		final Model<Sentence, LogicalExpression> model = (Model<Sentence, LogicalExpression>) get(params
				.get("model"));

		// The learning
		final ILearner<Sentence, SingleSentence, Model<Sentence, LogicalExpression>> learner = (ILearner<Sentence, SingleSentence, Model<Sentence, LogicalExpression>>) get(params
				.get("learner"));

		return new Job(params.get("id"), new HashSet<String>(
				params.getSplit("dep")), this,
				createJobOutputFile(params.get("id")),
				createJobLogFile(params.get("id"))) {

			@Override
			protected void doJob() {
				final long startTime = System.currentTimeMillis();

				// Start job
				LOG.info("============ (Job %s started)", getId());

				// Do the learning
				learner.train(model);

				// Log the final model
				LOG.info("Final model:\n%s", model);

				// Output total run time
				LOG.info("Total run time %.4f seconds",
						(System.currentTimeMillis() - startTime) / 1000.0);

				// Job completed
				LOG.info("============ (Job %s completed)", getId());

			}
		};
	}

}
