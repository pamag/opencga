package org.opencb.opencga.storage.mongodb.variant.load;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import org.apache.commons.lang3.StringUtils;
import org.bson.BsonArray;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.FileEntry;
import org.opencb.biodata.models.variant.avro.VariantType;
import org.opencb.biodata.tools.variant.merge.VariantMerger;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.datastore.mongodb.MongoDBCollection;
import org.opencb.commons.run.ParallelTaskRunner;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.variant.VariantStorageManager;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptor;
import org.opencb.opencga.storage.mongodb.variant.MongoDBVariantWriteResult;
import org.opencb.opencga.storage.mongodb.variant.VariantMongoDBAdaptor;
import org.opencb.opencga.storage.mongodb.variant.converters.DocumentToSamplesConverter;
import org.opencb.opencga.storage.mongodb.variant.converters.DocumentToStudyVariantEntryConverter;
import org.opencb.opencga.storage.mongodb.variant.converters.DocumentToVariantConverter;
import org.opencb.opencga.storage.mongodb.variant.converters.VariantStringIdComplexTypeConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.opencb.opencga.storage.mongodb.variant.converters.DocumentToSamplesConverter.UNKNOWN_GENOTYPE;
import static org.opencb.opencga.storage.mongodb.variant.converters.DocumentToStudyVariantEntryConverter.*;
import static org.opencb.opencga.storage.mongodb.variant.converters.DocumentToVariantConverter.IDS_FIELD;
import static org.opencb.opencga.storage.mongodb.variant.converters.DocumentToVariantConverter.STUDIES_FIELD;
import static org.opencb.opencga.storage.mongodb.variant.load.MongoDBVariantStageLoader.*;

/**
 * Created on 07/04/16.
 *
 * Merges data from the Stage collection into the Variant collection.
 *
 * There are different situations depending on how the data is coming or depending on
 * the current variants configuration.
 *
 * ### Depending on the configuration of the variants
 *
 * Easy scenarios:
 *
 *                +---------------+------+
 *                |     Stage     | Vars |
 *                +-------+-------+------+
 *                | File1 | File2 | Vars |
 *  +-------------+-------+-------+------+
 *  | 1:100:A:C   | DATA  | DATA  | DATA |  <--- A1) Update variants with data from File1 and File2
 *  +-------------+-------+-------+------+
 *  | 1:125:A:C   | DATA  | DATA  | ---- |  <--- A2) New variant
 *  +-------------+-------+-------+------+
 *  | 1:150:G:T   | ----  | ----  | DATA |  <--- A3) Fill Gaps. If new study, skip.
 *  +-------------+-------+-------+------+
 *
 *  Some partial information from the stage collection. Fill gaps
 *  +-------------+-------+-------+------+
 *  | 1:200:A:C   | ----  | DATA  | DATA |  <--- B1) Update variants with data from File2 and missing from File1
 *  +-------------+-------+-------+------+
 *  | 1:225:A:C   | DATA  | ----  | ---- |  <--- B2) New variant with missing information from the File2
 *  +-------------+-------+-------+------+
 *  | 1:250:G:T   | ----  | ----  | DATA |  <--- B3) Missing information. Fill Gaps. If new study, skip.
 *  +-------------+-------+-------+------+
 *
 *  Overlapping variants
 *  +-------------+-------+-------+------+
 *  | 1:300:A:C   | ----  | DATA  | DATA |  <---
 *  +-------------+-------+-------+------+      |- C1) Simple merge variants
 *  | 1:300:A:T   | DATA  | ----  | DATA |  <---
 *  +=============+=======+=======+======+
 *  | 1:400:A:C   | DATA  | DATA  | DATA |  <---
 *  +-------------+-------+-------+------+      |- C2) Simple merge variants. File1 has duplicated information for this variants
 *  | 1:400:A:T   | DATA  | ----  | DATA |  <---   Ideally, variants for File1 have correct secondary alternate. Discard one variant.
 *  +=============+=======+=======+======+
 *  | 1:500:A:C   | ----  | ----  | DATA |  <---
 *  +-------------+-------+-------+------+      |- C3) New overlapped region. Fetch data from the database and merge.
 *  | 1:500:A:T   | DATA  | DATA  | ---- |  <---   Fill gaps for indexed files
 *  +=============+=======+=======+======+
 *  | 1:600:A:C   | ----  | ----  | DATA |  <---
 *  +-------------+-------+-------+------+      |- C4) New overlapped region. Fetch data from the database and merge.
 *  | 1:600:A:T   | DATA  | ----  | ---- |  <---   Fill gaps for indexed files and file2
 *  +=============+=======+=======+======+
 *  | 1:700:A:C   | ----  | ----  | DATA |  <---
 *  +-------------+-------+-------+------+      |- C5) Already existing overlapped region. No extra information. No overlapping variants.
 *  | 1:700:A:T   | ----  | ----  | DATA |  <---   Fill gaps for file1 and file2
 *  +=============+=======+=======+======+
 *
 *  Duplicated variants. Do not load duplicated variants.
 *  +-------------+-------+-------+------+
 *  | 1:200:A:C   | ----  | DATA* | DATA |  <--- D1) Duplicated variants in file 2. Fill gaps for file1 and file2.
 *  +-------------+-------+-------+------+           Skip if new study (nonInserted++)
 *  | 1:250:G:T   | ----  | DATA* | ---- |  <--- D2) Duplicated variants in file 2, missing for the rest.
 *  +-------------+-------+-------+------+           Skip variant! (nonInserted++)
 *  | 1:225:A:C   | DATA  | DATA* | DATA |  <--- D3) Duplicated variants in file 2. Fill gaps for file2 and insert file1.
 *  +-------------+-------+-------+------+
 *  | 1:225:A:C   | DATA  | DATA* | ---- |  <--- D4) Duplicated variants in file 2. Fill gaps for file2 and insert file1.
 *  +-------------+-------+-------+------+
 *
 *
 * ### Depending on how the data is splitted in files, and how the files are sent.
 *
 * The data can came splitted by chromosomes and/or by batches of samples. For the
 * next tables, columns are batches of samples, and the rows are different regions,
 * for example, chromosomes.
 * The cells represents a file. If the file is already merged on the database uses an X,
 * or if it's being loaded right now, an O.
 *
 * In each scenario, must be aware when filling gaps (for new or missing variants). Have to
 * select properly the "indexed files" for each region.
 *
 * X = File loaded and merged
 * O = To be merged
 *
 * a) Merging one file having other merged files from a same sample set and chromosome.
 *        +---+---+---+
 *        |S1 |S2 |S3 |
 * +------+---+---+---+
 * | Chr1 | X | X |   |
 * +------+---+---+---+
 * | Chr2 | X | O |   |
 * +------+---+---+---+
 * | Chr3 | X |   |   |
 * +------+---+---+---+
 * Indexed files = {f_21}
 *
 * a.2) Merging different files from the same sample set.
 *      Must be aware when filling gaps (for new or missing variants).
 *      In this case, merge chromosome by chromosome in different calls.
 *        +---+---+---+
 *        |S1 |S2 |S3 |
 * +------+---+---+---+
 * | Chr1 | X | X | X |
 * +------+---+---+---+
 * | Chr2 | X | X | O |
 * +------+---+---+---+
 * | Chr3 | X | X | O |
 * +------+---+---+---+
 * Indexed files in Chr2 = {f_21, f_22}
 * Indexed files in Chr3 = {f_31, f_32}
 *
 * b) Merging one or more files from the same chromosome
 *    Having the indexed files in this region, no special consideration.
 *        +---+---+---+---+
 *        |S1 |S2 |S3 |S4 |
 * +------+---+---+---+---+
 * | Chr1 | X | X | X | X |
 * +------+---+---+---+---+
 * | Chr2 | X | X | O | O |
 * +------+---+---+---+---+
 * | Chr3 | X |   |   |   |
 * +------+---+---+---+---+
 * Indexed files = {f_21, f_22}
 *
 * c) Mix different regions and Sample sets
 *    Same situation as case a.2
 *        +---+---+---+
 *        |S1 |S2 |S3 |
 * +------+---+---+---+
 * | Chr1 | X | X | O |
 * +------+---+---+---+
 * | Chr2 | X | X |   |
 * +------+---+---+---+
 * | Chr3 | X | O |   |
 * +------+---+---+---+
 * Indexed files in Chr1 = {f_11, f_12}
 * Indexed files in Chr3 = {f_31}
 *
 * c) Mix different regions sizes. A single chromosome and a whole genome.
 *    Do not allow this!
 *        +---+---+---+
 *        |S1 |S2 |S3 |
 * +------+---+---+---+
 * | Chr1 | X | X | O |
 * +------+---+---+ O |
 * | Chr2 | X | O | O |
 * +------+---+---+ O |
 * | Chr3 | X | X | O |
 * +------+---+---+---+
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class MongoDBVariantMerger implements ParallelTaskRunner.Task<Document, MongoDBVariantWriteResult> {

    public static final QueryOptions QUERY_OPTIONS = new QueryOptions();
    private final VariantDBAdaptor dbAdaptor;
    private final MongoDBCollection collection;

    /** Study to be merged. */
    private final Integer studyId;
    /** Files to be merged. */
    private final List<Integer> fileIds;
    /** Indexed files in the region that we are merging. */
    private final Set<Integer> indexedFiles;
    private final DocumentToVariantConverter variantConverter;
    private final DocumentToStudyVariantEntryConverter studyConverter;
    private final StudyConfiguration studyConfiguration;
    private final boolean excludeGenotypes;

    // Variables that must be aware of concurrent modification
    private final MongoDBVariantWriteResult result;
    private final Map<Integer, LinkedHashMap<String, Integer>> samplesPositionMap;
    private final List<Integer> indexedSamples;

    public static final int DEFAULT_LOGING_BATCH_SIZE = 5000;
    private final AtomicInteger variantsCount;
    private long loggingBatchSize;
    private final Future<Long> futureNumTotalVariants;
    private final long aproxNumTotalVariants;
    private long numTotalVariants;

    private final Logger logger = LoggerFactory.getLogger(MongoDBVariantMerger.class);
    private final VariantMerger variantMerger;
    private final List<String> format;
    private boolean resume;
    private static final QueryOptions UPSERT_AND_RELPACE = new QueryOptions(MongoDBCollection.UPSERT, true)
            .append(MongoDBCollection.REPLACE, true);
    private static final QueryOptions UPSERT = new QueryOptions(MongoDBCollection.UPSERT, true);

    /**
     * Private class for grouping mongodb operations.
     * Allows thread-safe operations.
     */
    private class MongoDBOperations {


        // Document may exist, study does not exist
        private class NewStudy {
            private List<String> ids = new LinkedList<>();
            private List<Bson> queries = new LinkedList<>();
            private List<Bson> updates = new LinkedList<>();
            // Used if the document does not exist
            // This collection may be smaller than the previous collections
            private List<Document> variants = new LinkedList<>();
        }

        // Document and study exist
        private class ExistingStudy {
            private List<String> ids = new LinkedList<>();
            private List<Bson> queries = new LinkedList<>();
            private List<Bson> updates = new LinkedList<>();
        }

//        private List<Document> inserts =  new LinkedList<>();

        // Document may exist, study does not exist
        private NewStudy newStudy = new NewStudy();

        // Document and study exist
        private ExistingStudy existingStudy = new ExistingStudy();

        private int skipped = 0;
        private int nonInserted = 0;
        /** Extra insertions due to overlapped variants. */
        private int overlappedVariants = 0;
        /** Missing variants. See A3) */
        private long missingVariants = 0;
    }

    private MongoDBVariantMerger(VariantDBAdaptor dbAdaptor, StudyConfiguration studyConfiguration, List<Integer> fileIds,
                                 MongoDBCollection collection, Set<Integer> indexedFiles, Future<Long> futureNumTotalVariants,
                                 long aproxNumTotalVariants, boolean resume) {
        this.dbAdaptor = Objects.requireNonNull(dbAdaptor);
        this.studyConfiguration = Objects.requireNonNull(studyConfiguration);
        this.fileIds = Objects.requireNonNull(fileIds);
        this.collection = Objects.requireNonNull(collection);
        this.indexedFiles = Objects.requireNonNull(indexedFiles);

        excludeGenotypes = getExcludeGenotypes(studyConfiguration);
        format = buildFormat(studyConfiguration);
        indexedSamples = Collections.unmodifiableList(buildIndexedSamplesList(fileIds));
        studyId = studyConfiguration.getStudyId();

        DocumentToSamplesConverter samplesConverter = new DocumentToSamplesConverter(this.studyConfiguration);
        studyConverter = new DocumentToStudyVariantEntryConverter(false, samplesConverter);
        variantConverter = new DocumentToVariantConverter(studyConverter, null);
        result = new MongoDBVariantWriteResult();
        samplesPositionMap = new HashMap<>();

        variantsCount = new AtomicInteger(0);
        this.futureNumTotalVariants = futureNumTotalVariants;
        this.aproxNumTotalVariants = aproxNumTotalVariants;
        variantMerger = new VariantMerger();

        this.resume = resume;
    }

    public MongoDBVariantMerger(VariantDBAdaptor dbAdaptor, StudyConfiguration studyConfiguration, List<Integer> fileIds,
                                MongoDBCollection collection, long numTotalVariants, Set<Integer> indexedFiles, boolean resume) {
        this(dbAdaptor, studyConfiguration, fileIds, collection, indexedFiles, null, 0, resume);
        this.numTotalVariants = numTotalVariants;
        loggingBatchSize = Math.max(numTotalVariants / 200, DEFAULT_LOGING_BATCH_SIZE);
    }

    public MongoDBVariantMerger(VariantDBAdaptor dbAdaptor, StudyConfiguration studyConfiguration, List<Integer> fileIds,
                                MongoDBCollection collection, Future<Long> futureNumTotalVariants, long approximatedNumVariants,
                                Set<Integer> indexedFiles, boolean resume) {
        this(dbAdaptor, studyConfiguration, fileIds, collection, indexedFiles, futureNumTotalVariants, approximatedNumVariants, resume);
        loggingBatchSize = DEFAULT_LOGING_BATCH_SIZE;
    }

    public MongoDBVariantWriteResult getResult() {
        return result;
    }

    @Override
    public List<MongoDBVariantWriteResult> apply(List<Document> batch) {
        try {
            return Collections.singletonList(load(batch));
        } catch (Exception e) {
            if (batch.isEmpty()) {
                logger.error("Fail loading empty batch");
            } else {
                logger.error("Fail loading batch from " + batch.get(0).get("_id") + " to " + batch.get(batch.size() - 1).get("_id"));
            }
            throw e;
        }
    }

    @Override
    public void post() {
        VariantMongoDBAdaptor.createIndexes(new QueryOptions(), collection);
    }

    public MongoDBVariantWriteResult load(List<Document> variants) {

        // Set of operations to be executed in the Database
        MongoDBOperations mongoDBOps = new MongoDBOperations();

        Variant previousVariant = null;
        Document previousDocument = null;
        int start = 0;
        int end = 0;
        String chromosome = null;
        List<Document> overlappedVariants = null;

        for (Document document : variants) {
            Variant variant = STRING_ID_CONVERTER.convertToDataModelType(document);
            Document study = document.get(Integer.toString(studyId), Document.class);
            if (study != null) {
                if (previousVariant != null && variant.overlapWith(chromosome, start, end, true)) {
                    // If the variant overlaps with the last one, add to the overlappedVariants list.
                    // Do not process any variant!
                    if (overlappedVariants == null) {
                        overlappedVariants = new LinkedList<>();
                        overlappedVariants.add(previousDocument);
                    }
                    overlappedVariants.add(document);
                    start = Math.min(start, variant.getStart());
                    end = Math.max(end, getEnd(variant));
                    previousDocument = document;
                    previousVariant = variant;

                    continue;
                } else {
                    // If the current variant does not overlap with the last one, we can load the previous variant (or region)
                    if (overlappedVariants != null) {
                        processOverlappedVariants(overlappedVariants, mongoDBOps);
                    } else if (previousDocument != null) {
                        processVariantTryCatch(previousDocument, previousVariant, mongoDBOps);
                    }
                    overlappedVariants = null;
                }

                previousDocument = document;
                previousVariant = variant;
                chromosome = variant.getChromosome();
                start = variant.getStart();
                end = getEnd(variant);
            }

        }

        // Process remaining variants
        if (overlappedVariants != null) {
            processOverlappedVariants(overlappedVariants, mongoDBOps);
        } else if (previousDocument != null) {
            processVariantTryCatch(previousDocument, previousVariant, mongoDBOps);
        }

        // Execute MongoDB Operations
        return executeMongoDBOperations(mongoDBOps);
    }

    public Integer getEnd(Variant variant) {
//        if (variant.getType().equals(VariantType.SYMBOLIC) || variant.getType().equals(VariantType.NO_VARIATION)) {
//            return variant.getEnd();
//        } else {
//            return variant.getStart() + Math.max(variant.getReference().length() - 1, -1 /* 0 */);
//        }
        if (EnumSet.of(VariantType.SYMBOLIC, VariantType.CNV).contains(variant.getType())) {
            return variant.getStart();
        } else {
            return variant.getEnd();
        }
    }

    protected void processVariantTryCatch(Document document, Variant emptyVar, MongoDBOperations mongoDBOps) {
        try {
            processVariant(document, emptyVar, mongoDBOps);
        } catch (Exception e) {
            logger.error("Error processing variant " + emptyVar);
            throw e;
        }
    }
    /**
     * Given a document from the stage collection, transforms the document into a set of MongoDB operations.
     *
     * It may be a new variant document in the database, a new study in the document, or just an update of an existing study variant.
     *
     * @param document          Document to load
     * @param emptyVar          Parsed empty variant of the document. Only chr, pos, ref, alt
     * @param mongoDBOps        Set of MongoDB operations to update
     */
    protected void processVariant(Document document, Variant emptyVar, MongoDBOperations mongoDBOps) {
        Document study = document.get(Integer.toString(studyId), Document.class);

        // New variant in the study.
        boolean newStudy = isNewStudy(study);
        // New variant in the collection if new variant and document size is 2 {_id, study}
        boolean newVariant = isNewVariant(document, newStudy);


        Set<String> ids = new HashSet<>();
        List<Document> fileDocuments = new LinkedList<>();
        List<Document> alternateDocuments = new LinkedList<>();
        Document gts = new Document();

        // Loop for each file that have to be merged
        for (Integer fileId : fileIds) {

            // Different actions if the file is present or missing in the document.
            if (study.containsKey(fileId.toString())) {
                //Duplicated documents are treated like missing. Increment the number of duplicated variants
                List<Binary> duplicatedVariants = getListFromDocument(study, fileId.toString());
                if (duplicatedVariants.size() > 1) {
                    mongoDBOps.nonInserted += duplicatedVariants.size();
                    addSampleIdsGenotypes(gts, UNKNOWN_GENOTYPE, getSamplesInFile(fileId));
                    logger.warn("Found {} duplicated variants for file {} in variant {}", duplicatedVariants.size(), fileId, emptyVar);
                    continue;
                }

                Binary file = duplicatedVariants.get(0);
                Variant variant = VARIANT_CONVERTER_DEFAULT.convertToDataModelType(file);
                if (variant.getType().equals(VariantType.NO_VARIATION) || variant.getType().equals(VariantType.SYMBOLIC)) {
                    mongoDBOps.skipped++;
                    continue;
                }
                if (StringUtils.isNotEmpty(variant.getId()) && !variant.getId().equals(variant.toString())) {
                    ids.add(variant.getId());
                }
                if (variant.getNames() != null) {
                    ids.addAll(variant.getNames());
                }
                emptyVar.setType(variant.getType());
                variant.getStudies().get(0).setSamplesPosition(getSamplesPosition(fileId));
                Document newDocument = studyConverter.convertToStorageType(variant.getStudies().get(0));

                fileDocuments.add((Document) getListFromDocument(newDocument, FILES_FIELD).get(0));
                alternateDocuments = getListFromDocument(newDocument, ALTERNATES_FIELD);

                if (newDocument.containsKey(GENOTYPES_FIELD)) {
                    for (Map.Entry<String, Object> entry : newDocument.get(GENOTYPES_FIELD, Document.class).entrySet()) {
                        addSampleIdsGenotypes(gts, entry.getKey(), (List<Integer>) entry.getValue());
                    }
                }

            } else {
//                logger.debug("File {} not in variant {}", fileId, emptyVar);
                addSampleIdsGenotypes(gts, UNKNOWN_GENOTYPE, getSamplesInFile(fileId));
            }

        }

        if (newStudy) {
            //If it is a new variant for the study, add the already loaded samples as UNKNOWN
            addSampleIdsGenotypes(gts, UNKNOWN_GENOTYPE, getIndexedSamples());
        }

        updateMongoDBOperations(emptyVar, new ArrayList<>(ids), fileDocuments, alternateDocuments, gts, newStudy, newVariant, mongoDBOps);
    }

    protected void processOverlappedVariants(List<Document> overlappedVariants, MongoDBOperations mongoDBOps) {
        for (Document document : overlappedVariants) {
            try {
                processOverlappedVariants(document, overlappedVariants, mongoDBOps);
            } catch (Exception e) {
                Variant mainVariant = STRING_ID_CONVERTER.convertToDataModelType(document);
                List<Variant> variants = overlappedVariants.stream()
                        .map(STRING_ID_CONVERTER::convertToDataModelType)
                        .collect(Collectors.toList());
                logger.error("Error processing variant " + mainVariant + " in overlapped variants " + variants);
                throw e;
            }
        }
    }

    /**
     * Given a list of documents from the stage collection, and one variant from the list of documents,
     * merges into the main variant and transforms into a set of MongoDB operations.
     *
     * It may be a new variant document in the database, a new study in the document, or just an update of an existing study variant.
     *
     * @param mainDocument          Main document to add.
     * @param overlappedVariants    Overlapping documents from Stage collection.
     * @param mongoDBOps            Set of MongoDB operations to update
     */
    protected void processOverlappedVariants(Document mainDocument, List<Document> overlappedVariants, MongoDBOperations mongoDBOps) {

        Variant mainVariant = STRING_ID_CONVERTER.convertToDataModelType(mainDocument);

        // Merge documents
        Map<Document, Variant> mergedVariants = mergeOverlappedVariants(mainVariant, overlappedVariants);

        int variantsWithValidData = getVariantsWithValidData(mergedVariants.keySet());

        // Get the main variant from merged variants.
        Variant variant = mergedVariants.get(mainDocument);

        if (variant == null) {
            throw new IllegalStateException("Main variant not found after merge! " + mainVariant + ". "
                    + "Merged variants: " + mergedVariants.values());
//            mongoDBOps.overlappedVariants++;
        } else {
            Document study = mainDocument.get(studyId.toString(), Document.class);

            // New variant in the study.
            boolean newStudy = isNewStudy(study);
            // New variant in the collection if new variant and document size is 2 {_id, study}
            boolean newVariant = isNewVariant(mainDocument, newStudy);

            // A variant counts as duplicated if is duplicated or missing for all the files.
            int duplicatedVariants = 0;
            List<String> duplicatedVariantsList = new ArrayList<>();
            int duplicatedFiles = 0;
            int missingFiles = 0;
            for (Integer fileId : fileIds) {
                List<Binary> files = getListFromDocument(study, fileId.toString());
                if (files == null || files.isEmpty()) {
                    missingFiles++;
                } else if (files.size() > 1) {
                    duplicatedVariants += files.size();
                    duplicatedFiles++;
//                        // If there are more than one variant for this file, increment the number of nonInserted variants.
//                        // Duplicated variant
                    logger.warn("Found {} duplicated variants for file {} in variant {}.",
                            files.size(), fileId, mainVariant);
                    for (Binary binary : files) {
                        Variant duplicatedVariant = VARIANT_CONVERTER_DEFAULT.convertToDataModelType(binary);
                        String call = duplicatedVariant.getStudies().get(0).getFiles().get(0).getCall();
                        if (call == null) {
                            call = duplicatedVariant.toString();
                        }
                        duplicatedVariantsList.add(call);
                    }
                }
            }

            // An overlapping variant will be considered missing if is missing or duplicated for all the files.
            final boolean missingOverlappingVariant;

            if (duplicatedFiles + missingFiles == fileIds.size()) {
                // C3.1), C4.1), C5), B3), D1), D2)
                missingOverlappingVariant = true;
                if (duplicatedFiles > 0) {
                    // D1), D2)
                    logger.error("Duplicated! " + mainVariant + " " + duplicatedVariantsList);
                    mongoDBOps.nonInserted += duplicatedVariants;
                }
                // No information for this variant
                if (newStudy) {
                    // B3), D1), D2)
                    return;
                }
                // else {
                //      Do not skip. Fill gaps.
                //      No new overlapped variants.
                // }

                if (variantsWithValidData != 0) {
                    // Scenarios C3.1), C4.1)
                    logger.warn("Missing overlapped variant! " + mainVariant);
                    mongoDBOps.overlappedVariants++;
                }
                // else {
                //      If the files to be loaded where not present in the current variant, there is not overlapped variant.
                //      See scenario C5)
                // }
            } else {
                missingOverlappingVariant = false;
            }

            Document gts = new Document();
            List<Document> fileDocuments = new LinkedList<>();
            List<Document> alternateDocuments = null;
            StudyEntry studyEntry = variant.getStudies().get(0);

            // For all the files that are being indexed
            for (Integer fileId : fileIds) {
                FileEntry file = studyEntry.getFile(fileId.toString());
                if (file != null) {
                    Document studyDocument = studyConverter.convertToStorageType(studyEntry, file, getSampleNamesInFile(fileId));
                    if (studyDocument.containsKey(GENOTYPES_FIELD)) {
                        studyDocument.get(GENOTYPES_FIELD, Document.class)
                                .forEach((gt, sampleIds) -> addSampleIdsGenotypes(gts, gt, (Collection<Integer>) sampleIds));
                    }
                    fileDocuments.addAll(getListFromDocument(studyDocument, FILES_FIELD));
                    alternateDocuments = getListFromDocument(studyDocument, ALTERNATES_FIELD);
                } else {
                    addSampleIdsGenotypes(gts, UNKNOWN_GENOTYPE, getSamplesInFile(fileId));
                }
            }

            // For the rest of the files not indexed, only is this variant is new in this study,
            // add all the already indexed files information, if present in this variant.
            if (newStudy) {
                for (Integer fileId : indexedFiles) {
                    FileEntry file = studyEntry.getFile(fileId.toString());
                    if (file != null) {
                        Document studyDocument = studyConverter.convertToStorageType(studyEntry, file, getSampleNamesInFile(fileId));
                        if (studyDocument.containsKey(GENOTYPES_FIELD)) {
                            studyDocument.get(GENOTYPES_FIELD, Document.class)
                                    .forEach((gt, sampleIds) -> addSampleIdsGenotypes(gts, gt, (Collection<Integer>) sampleIds));
                        }
                        fileDocuments.addAll(getListFromDocument(studyDocument, FILES_FIELD));
                    } else {
                        addSampleIdsGenotypes(gts, UNKNOWN_GENOTYPE, getSamplesInFile(fileId));
                    }
                }
            }
            updateMongoDBOperations(mainVariant, variant.getIds(), fileDocuments, alternateDocuments, gts, newStudy, newVariant,
                    mongoDBOps);
        }

    }

    /**
     * Given a collection of documents from the stage collection, returns the number of documents (variants) with valid data.
     * i.e. : At least one file not duplicated with information
     *
     * @param documents Variants from the stage collection
     * @return  Number of variants with valid data.
     */
    private int getVariantsWithValidData(Collection<Document> documents) {
        int variantsWithValidData = 0;
        for (Document document : documents) {
            Document study = document.get(Integer.toString(studyId), Document.class);
            boolean existingFiles = false;
            for (Integer fileId : fileIds) {
                List<Binary> files = getListFromDocument(study, fileId.toString());
                if (files != null && files.size() == 1) {
                    existingFiles = true;
                    break;
                }
            }
            if (existingFiles) {
                variantsWithValidData++;
            }
        }
        return variantsWithValidData;
    }

    /**
     * Given a list of overlapped documents from the stage collection, merge resolving the overlapping positions.
     *
     * If there are any conflict with overlapped positions, will try to select always the mainVariant.
     *
     * @see {@link VariantMerger}
     *
     * @param mainVariant           Main variant to resolve conflicts.
     * @param overlappedVariants    Overlapping documents from Stage collection.
     * @return  For each document, its corresponding merged variant
     */
    protected Map<Document, Variant> mergeOverlappedVariants(Variant mainVariant, List<Document> overlappedVariants) {
//        System.out.println("--------------------------------");
//        System.out.println("Overlapped region = " + overlappedVariants
//                .stream()
//                .map(doc -> STRING_ID_CONVERTER.convertToDataModelType(doc.getString("_id")))
//                .collect(Collectors.toList()));

        // The overlapping region will be new if any of the variants is new for the study
        boolean newOverlappingRegion = false;
        // The overlapping region will be completely new if ALL the variants are new for the study
        boolean completelyNewOverlappingRegion = true;

        Map<Integer, List<Variant>> variantsPerFile = new HashMap<>();
        for (Integer fileId : fileIds) {
            variantsPerFile.put(fileId, new LinkedList<>());
        }

        // Linked hash map to preserve the order
        Map<Document, Variant> mergedVariants = new LinkedHashMap<>();
        List<Boolean> newStudies = new ArrayList<>(overlappedVariants.size());

        // For each variant, create an empty variant that will be filled by the VariantMerger
        for (Document document : overlappedVariants) {
            Variant var = STRING_ID_CONVERTER.convertToDataModelType(document);
            if (!mainVariant.overlapWith(var, true)) {
                // Skip those variants that do not overlap with the given main variant
                continue;
            }

            Document study = document.get(Integer.toString(studyId), Document.class);

            // New variant in the study.
            boolean newStudy = isNewStudy(study);
            newStudies.add(newStudy);
            // Its a new OverlappingRegion if at least one variant is new in this study
            newOverlappingRegion |= newStudy;
            // Its a completely new OverlappingRegion if all the variants are new in this study
            completelyNewOverlappingRegion &= newStudy;

            HashSet<String> ids = new HashSet<>();
            StudyEntry se = new StudyEntry(studyId.toString(), new LinkedList<>(), format);
            se.setSamplesPosition(new HashMap<>());
            var.addStudyEntry(se);

            mergedVariants.put(document, var);

            for (Integer fileId : fileIds) {
                List<Binary> files = getListFromDocument(study, fileId.toString());
                if (files != null && files.size() == 1) {
                    // If there is only one variant for this file, add to the map variantsPerFile
                    Variant variant = VARIANT_CONVERTER_DEFAULT.convertToDataModelType(files.get(0));
                    variant.getStudies().get(0).setSamplesPosition(getSamplesPosition(fileId));
                    variantsPerFile.get(fileId).add(variant);
                    ids.addAll(variant.getIds());
                }
            }
            var.setIds(new ArrayList<>(ids));
        }

        List<Variant> variantsToMerge = new LinkedList<>();
        for (Integer fileId : fileIds) {
            List<Variant> variantList = variantsPerFile.get(fileId);
            switch (variantList.size()) {
                case 0:
                    break;
                case 1:
                    variantsToMerge.add(variantList.get(0));
                    break;
                default:
                    // If there are overlapping variants, select the mainVariant if possible.
                    Variant var = null;
                    for (Variant variant : variantList) {
                        if (sameVariant(variant, mainVariant)) {
                            var = variant;
                        }
                    }
                    // If not found, get the first
                    if (var == null) {
                        var = variantList.get(0);
//                        logger.info("Variant " + mainVariant + " not found in " + variantList);
                    }
                    variantsToMerge.add(var);

                    // Get the original call from the first variant
                    String call = var.getStudies().get(0).getFiles().get(0).getCall();
                    if (call != null) {
                        if (call.isEmpty()) {
                            call = null;
                        } else {
                            call = call.substring(0, call.lastIndexOf(':'));
                        }
                    }

                    boolean prompted = false;
                    for (int i = 1; i < variantList.size(); i++) {
                        Variant auxVar = variantList.get(i);
                        // Check if variants where part of the same multiallelic variant
                        String auxCall = var.getStudies().get(0).getFiles().get(0).getCall();
                        if (!prompted && (auxCall == null || call == null || !auxCall.startsWith(call))) {
                            logger.warn("Overlapping variants in file {} : {}", fileId, variantList);
                            prompted = true;
                        }
//                        // Those variants that do not overlap with the selected variant won't be inserted
//                        if (!auxVar.overlapWith(var, true)) {
//                            mongoDBOps.nonInserted++;
//                            logger.warn("Skipping overlapped variant " + auxVar);
//                        }
                    }
                    break;
            }
        }


        /*
         * If is a new overlapping region and there are some file already indexed
         * Fetch the information from the database regarding the loaded variants of this region.
         *
         *      +---+---+---+---+
         *      | A | B | C | D |
         * +----+---+---+---+---+
         * | V1 | X | X |   |   |
         * +----+---+---+---+---+
         * | V2 | X | X |   | X |
         * +----+---+---+---+---+
         * | V3 |   |   | X |   |
         * +----+---+---+---+---+
         *
         * - Files A and B are loaded
         * - Files C and D are being loaded
         * - Variants V1,V2,V3 are overlapping
         *
         * In order to merge the data properly, we need to get from the server the information about
         * the variants {V1, V2} for the files {A, B}.
         *
         * Because the variants {V1, V2} are already loaded, the information that we need is duplicated
         * in both variants, so we only need to get one of them.
         *
         */
        if (!completelyNewOverlappingRegion && newOverlappingRegion && !indexedFiles.isEmpty()) {
            int i = 0;
            for (Variant variant : mergedVariants.values()) {
                // If the variant is not new in this study, query to the database for the loaded info.
                if (!newStudies.get(i)) {
                    QueryResult<Variant> queryResult = dbAdaptor.get(new Query()
                            .append(VariantDBAdaptor.VariantQueryParams.ID.key(), variant.toString())
                            .append(VariantDBAdaptor.VariantQueryParams.RETURNED_STUDIES.key(), studyId), null);
                    if (queryResult.getResult().size() == 1 && queryResult.first().getStudies().size() == 1) {
                        variantsToMerge.add(queryResult.first());
                    } else {
                        if (queryResult.getResult().isEmpty()) {
                            throw new IllegalStateException("Variant " + variant + " not found!");
                        } else {
                            throw new IllegalStateException("Variant " + variant + " found wrong! : " + queryResult.getResult());
                        }
                    }
                    // Because the loaded variants were an overlapped region, all the information required is in every variant.
                    // Fetch only one variant
                    break;
                }
                i++;
            }
        }

        // Finally, merge variants
        for (Variant mergedVariant : mergedVariants.values()) {
            variantMerger.merge(mergedVariant, variantsToMerge);
        }

//        System.out.println("----------------");
//        for (Variant variant : mergedVariants.values()) {
//            System.out.println(variant.toJson());
//        }
//        System.out.println("----------------");
//        System.out.println("--------------------------------");

        return mergedVariants;
    }

    /**
     * Transform the set of genotypes and file objects into a set of mongodb operations.
     *
     * @param emptyVar            Parsed empty variant of the document. Only chr, pos, ref, alt
     * @param ids                 Variant identifiers seen for this variant
     * @param fileDocuments       List of files to be updated
     * @param secondaryAlternates SecondaryAlternates documents.
     * @param gts                 Set of genotypes to be updates
     * @param newStudy            If the variant is new for this study
     * @param newVariant          If the variant was never seen in the database
     * @param mongoDBOps          Set of MongoBD operations to update
     */
    protected void updateMongoDBOperations(Variant emptyVar, List<String> ids, List<Document> fileDocuments,
                                           List<Document> secondaryAlternates, Document gts, boolean newStudy, boolean newVariant,
                                           MongoDBOperations mongoDBOps) {
        if (newStudy) {
            // If there where no files and the study is new, do not add a new study.
            // It may happen if all the files in the variant where duplicated for this variant.
            if (!fileDocuments.isEmpty()) {
                Document studyDocument = new Document(STUDYID_FIELD, studyId)
                        .append(FILES_FIELD, fileDocuments);

                if (!excludeGenotypes) {
                    studyDocument.append(GENOTYPES_FIELD, gts);
                }

                if (secondaryAlternates != null && !secondaryAlternates.isEmpty()) {
                    studyDocument.append(ALTERNATES_FIELD, secondaryAlternates);
                }

                final String id;
                List<Bson> updates = new ArrayList<>();
                updates.add(push(STUDIES_FIELD, studyDocument));
                if (newVariant) {
                    Document variantDocument = variantConverter.convertToStorageType(emptyVar);
                    updates.add(addEachToSet(IDS_FIELD, ids));
                    for (Map.Entry<String, Object> entry : variantDocument.entrySet()) {
                        if (!entry.getKey().equals("_id") && !entry.getKey().equals(STUDIES_FIELD) && !entry.getKey().equals(IDS_FIELD)) {
                            Object value = entry.getValue();
                            if (value instanceof List) {
                                updates.add(setOnInsert(entry.getKey(), new BsonArray(((List) value))));
                            } else {
                                updates.add(setOnInsert(entry.getKey(), value));
                            }
                        }
                    }
                    mongoDBOps.newStudy.variants.add(variantDocument);
                    id = variantDocument.getString("_id");
                } else {
                    id = variantConverter.buildStorageId(emptyVar);
                }
                mongoDBOps.newStudy.ids.add(id);
                mongoDBOps.newStudy.queries.add(eq("_id", id));
                mongoDBOps.newStudy.updates.add(combine(updates));
            }
        } else {
            String id = variantConverter.buildStorageId(emptyVar);
            List<Bson> mergeUpdates = new LinkedList<>();
            mergeUpdates.add(addEachToSet(IDS_FIELD, ids));

            if (!excludeGenotypes) {
                for (String gt : gts.keySet()) {
                    List sampleIds = getListFromDocument(gts, gt);
                    if (resume) {
                        mergeUpdates.add(addEachToSet(STUDIES_FIELD + ".$." + GENOTYPES_FIELD + "." + gt,
                                sampleIds));
                    } else {
                        mergeUpdates.add(pushEach(STUDIES_FIELD + ".$." + GENOTYPES_FIELD + "." + gt,
                                sampleIds));
                    }
                }
            }
            if (secondaryAlternates != null && !secondaryAlternates.isEmpty()) {
                mergeUpdates.add(addEachToSet(STUDIES_FIELD + ".$." + ALTERNATES_FIELD, secondaryAlternates));
            }

            // These files not present are missing values.
            mongoDBOps.missingVariants += fileIds.size() - fileDocuments.size();

            if (!fileDocuments.isEmpty()) {
                mongoDBOps.existingStudy.ids.add(id);
                mongoDBOps.existingStudy.queries.add(and(eq("_id", id),
                        eq(STUDIES_FIELD + "." + STUDYID_FIELD, studyId)));

                if (resume) {
                    mergeUpdates.add(addEachToSet(STUDIES_FIELD + ".$." + FILES_FIELD, fileDocuments));
                } else {
                    mergeUpdates.add(pushEach(STUDIES_FIELD + ".$." + FILES_FIELD, fileDocuments));
                }
                mongoDBOps.existingStudy.updates.add(combine(mergeUpdates));
            } else {
                mongoDBOps.existingStudy.ids.add(id);
                mongoDBOps.existingStudy.queries.add(and(eq("_id", id),
                        eq(STUDIES_FIELD + "." + STUDYID_FIELD, studyId)));
                mongoDBOps.existingStudy.updates.add(combine(mergeUpdates));
            }
        }
    }

    /**
     * Execute the set of mongoDB operations.
     *
     * @param mongoDBOps MongoDB operations to execute
     * @return           MongoDBVariantWriteResult
     */
    protected MongoDBVariantWriteResult executeMongoDBOperations(MongoDBOperations mongoDBOps) {
        long newVariantsTime = -System.nanoTime();

        newVariantsTime += System.nanoTime();
        long existingVariants = -System.nanoTime();
        long newVariants = 0;
        if (!mongoDBOps.newStudy.queries.isEmpty()) {
            newVariants = executeMongoDBOperationsNewStudy(mongoDBOps, true);
        }
        existingVariants += System.nanoTime();
        long fillGapsVariants = -System.nanoTime();
        if (!mongoDBOps.existingStudy.queries.isEmpty()) {
            QueryResult<BulkWriteResult> update = collection.update(mongoDBOps.existingStudy.queries, mongoDBOps.existingStudy.updates,
                    QUERY_OPTIONS);
            if (update.first().getMatchedCount() != mongoDBOps.existingStudy.queries.size()) {
                onUpdateError("fill gaps", update, mongoDBOps.existingStudy.queries, mongoDBOps.existingStudy.ids);
            }
        }
        fillGapsVariants += System.nanoTime();

        long updatesNewStudyExistingVariant = mongoDBOps.newStudy.updates.size() - newVariants;
        long updatesWithDataExistingStudy = mongoDBOps.existingStudy.updates.size() - mongoDBOps.missingVariants;
        MongoDBVariantWriteResult writeResult = new MongoDBVariantWriteResult(newVariants,
                updatesNewStudyExistingVariant + updatesWithDataExistingStudy, mongoDBOps.missingVariants,
                mongoDBOps.overlappedVariants, mongoDBOps.skipped, mongoDBOps.nonInserted, newVariantsTime, existingVariants,
                fillGapsVariants);
        synchronized (result) {
            result.merge(writeResult);
        }

        int processedVariants = mongoDBOps.newStudy.queries.size() + mongoDBOps.existingStudy.queries.size();
        logProgress(processedVariants);
        return writeResult;
    }

    private int executeMongoDBOperationsNewStudy(MongoDBOperations mongoDBOps, boolean retry) {
        int newVariants = 0;
        try {
            if (resume) {
                // Ensure files exists
                try {
                    if (!mongoDBOps.newStudy.variants.isEmpty()) {
                        newVariants += mongoDBOps.newStudy.variants.size();
                        collection.insert(mongoDBOps.newStudy.variants, QUERY_OPTIONS);
                    }
                } catch (MongoBulkWriteException e) {
                    for (BulkWriteError writeError : e.getWriteErrors()) {
                        if (!ErrorCategory.fromErrorCode(writeError.getCode()).equals(ErrorCategory.DUPLICATE_KEY)) {
                            throw e;
                        } else {
                            // Not inserted variant
                            newVariants--;
                        }
                    }
                }

                // Update
                List<Bson> queriesExisting = new ArrayList<>(mongoDBOps.newStudy.queries.size());
                for (Bson bson : mongoDBOps.newStudy.queries) {
                    queriesExisting.add(and(bson, nin(STUDIES_FIELD + "." + FILES_FIELD + "." + FILEID_FIELD, fileIds)));
                }
                // Update those existing variants
                QueryResult<BulkWriteResult> update = collection.update(queriesExisting, mongoDBOps.newStudy.updates, QUERY_OPTIONS);
    //                if (update.first().getModifiedCount() != mongoDBOps.queriesExisting.size()) {
    //                    // FIXME: Don't know if there is some error inserting. Query already existing?
    //                    onUpdateError("existing variants", update, mongoDBOps.queriesExisting, mongoDBOps.queriesExistingId);
    //                }
            } else {
                QueryResult<BulkWriteResult> update = collection.update(mongoDBOps.newStudy.queries, mongoDBOps.newStudy.updates, UPSERT);
                if (update.first().getModifiedCount() + update.first().getUpserts().size() != mongoDBOps.newStudy.queries.size()) {
                    onUpdateError("existing variants", update, mongoDBOps.newStudy.queries, mongoDBOps.newStudy.ids);
                }
                // Add upserted documents
                newVariants += update.first().getUpserts().size();
            }
        } catch (MongoBulkWriteException e) {
            // Add upserted documents
            newVariants += e.getWriteResult().getUpserts().size();
            Set<String> duplicatedNonInsertedId = new HashSet<>();
            for (BulkWriteError writeError : e.getWriteErrors()) {
                if (!ErrorCategory.fromErrorCode(writeError.getCode()).equals(ErrorCategory.DUPLICATE_KEY)) {
                    throw e;
                } else {
                    Matcher matcher = DUP_KEY_WRITE_RESULT_ERROR_PATTERN.matcher(writeError.getMessage());
                    if (matcher.find()) {
                        String id = matcher.group(1);
                        duplicatedNonInsertedId.add(id);
                        logger.warn("Catch error : {}",  writeError.toString());
                        logger.warn("DupKey exception inserting '{}'. Retry!", id);
                    } else {
                        logger.error("WriteError with code {} does not match with the pattern {}",
                                writeError.getCode(), DUP_KEY_WRITE_RESULT_ERROR_PATTERN.pattern());
                        throw e;
                    }
                }
            }
            if (retry) {
                // Retry once!
                // With UPSERT=true, this command should never throw DuplicatedKeyException.
                // See https://jira.mongodb.org/browse/SERVER-14322
                // Remove inserted variants
                logger.warn("Retry! " + e);
                Iterator<String> iteratorId = mongoDBOps.newStudy.ids.iterator();
                Iterator<?> iteratorQuery = mongoDBOps.newStudy.queries.iterator();
                Iterator<?> iteratorUpdate = mongoDBOps.newStudy.updates.iterator();
                while (iteratorId.hasNext()) {
                    String id = iteratorId.next();
                    iteratorQuery.next();
                    iteratorUpdate.next();
                    if (!duplicatedNonInsertedId.contains(id)) {
                        iteratorId.remove();
                        iteratorQuery.remove();
                        iteratorUpdate.remove();
                    }
                }
                newVariants += executeMongoDBOperationsNewStudy(mongoDBOps, false);
            } else {
                throw e;
            }
        }
        return newVariants;
    }

    /**
     * Is a new variant for the study depending on the value of the field {@link MongoDBVariantStageLoader#NEW_STUDY_FIELD}.
     * @param study Study object
     * @return      If this is the first time that the variant has been seen in this study.
     */
    public static boolean isNewStudy(Document study) {
        return study.getBoolean(MongoDBVariantStageLoader.NEW_STUDY_FIELD, MongoDBVariantStageLoader.NEW_STUDY_DEFAULT);
    }

    public static boolean isNewVariant(Document document, boolean newStudy) {
        // If the document has only the study, _id, end, ref and alt fields.
        if (!newStudy || document.size() != 5) {
            for (Map.Entry<String, Object> entry : document.entrySet()) {
                if (!entry.getKey().equals(VariantStringIdComplexTypeConverter.ID_FIELD)
                        && !entry.getKey().equals(VariantStringIdComplexTypeConverter.END_FIELD)
                        && !entry.getKey().equals(VariantStringIdComplexTypeConverter.REF_FIELD)
                        && !entry.getKey().equals(VariantStringIdComplexTypeConverter.ALT_FIELD)) {
                    if (!isNewStudy((Document) entry.getValue())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean sameVariant(Variant variant, Variant other) {
        return variant.getChromosome().equals(other.getChromosome())
                && variant.getStart().equals(other.getStart())
                && variant.getReference().equals(other.getReference())
                && variant.getAlternate().equals(other.getAlternate());
    }

    protected void logProgress(int processedVariants) {
        long numTotalVariants = aproxNumTotalVariants;
        if (this.numTotalVariants <= 0) {
            try {
                if (futureNumTotalVariants.isDone()) {
                    synchronized (futureNumTotalVariants) {
                        if (this.numTotalVariants <= 0) {
                            numTotalVariants = futureNumTotalVariants.get();
                            this.numTotalVariants = numTotalVariants;
                            loggingBatchSize = Math.max(numTotalVariants / 200, DEFAULT_LOGING_BATCH_SIZE);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        } else {
            numTotalVariants = this.numTotalVariants;
        }
        int previousCount = variantsCount.getAndAdd(processedVariants);
        if ((previousCount + processedVariants) / loggingBatchSize != previousCount / loggingBatchSize) {
            logger.info("Write variants in VARIANTS collection " + (previousCount + processedVariants) + "/" + numTotalVariants + " "
                    + String.format("%.2f%%", ((float) (previousCount + processedVariants)) / numTotalVariants * 100.0));
        }
    }

    protected void addSampleIdsGenotypes(Document gts, String genotype, Collection<Integer> sampleIds) {
        if (sampleIds.isEmpty()) {
            return;
        }
        if (gts.containsKey(genotype)) {
            getListFromDocument(gts, genotype).addAll(sampleIds);
        } else {
            gts.put(genotype, new LinkedList<>(sampleIds));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> getListFromDocument(Document document, String key) {
        return document.get(key, List.class);
    }

//    protected void onInsertError(MongoDBOperations mongoDBOps, BulkWriteResult writeResult) {
//        logger.error("(Inserts = " + mongoDBOps.inserts.size() + ") "
//                + "!= (InsertedCount = " + writeResult.getInsertedCount() + ")");
//
//        StringBuilder sb = new StringBuilder("Missing Variant for insert : ");
//        for (Document insert : mongoDBOps.inserts) {
//            Long count = collection.count(eq("_id", insert.get("_id"))).first();
//            if (count != 1) {
//                logger.error("Missing insert " + insert.get("_id"));
//                sb.append(insert.get("_id")).append(", ");
//            }
//        }
//        throw new RuntimeException(sb.toString());
//    }

    protected void onUpdateError(String updateName, QueryResult<BulkWriteResult> update, List<Bson> queries, List<String> queryIds) {
        logger.error("(Updated " + updateName + " variants = " + queries.size() + " ) != "
                + "(ModifiedCount = " + update.first().getModifiedCount() + "). MatchedCount:" + update.first().getMatchedCount());
        logger.info("QueryIDs: {}", queryIds);
        List<QueryResult<Document>> queryResults = collection.find(queries, null);
        logger.info("Results: ", queryResults.size());

        for (QueryResult<Document> r : queryResults) {
            logger.info("result: ", r);
            if (!r.getResult().isEmpty()) {
                String id = r.first().get("_id", String.class);
                boolean remove = queryIds.remove(id);
                logger.info("remove({}): {}", id, remove);
            }
        }
        StringBuilder sb = new StringBuilder("Missing Variant for update : ");
        for (String id : queryIds) {
            logger.error("Missing Variant " + id);
            sb.append(id).append(", ");
        }
        throw new RuntimeException(sb.toString());
    }

    protected List<Integer> getIndexedSamples() {
        return indexedSamples;
    }

    private List<Integer> buildIndexedSamplesList(List<Integer> fileIds) {
        List<Integer> indexedSamples = new LinkedList<>(StudyConfiguration.getIndexedSamples(studyConfiguration).values());
        for (Integer fileId : fileIds) {
            indexedSamples.removeAll(getSamplesInFile(fileId));
        }
        indexedSamples.sort(Integer::compareTo);
        return indexedSamples;
    }

    protected LinkedHashSet<Integer> getSamplesInFile(Integer fileId) {
        return studyConfiguration.getSamplesInFiles().get(fileId);
    }

    protected Set<String> getSampleNamesInFile(Integer fileId) {
        return getSamplesInFile(fileId)
                .stream()
                .map(sampleId -> studyConfiguration.getSampleIds().inverse().get(sampleId))
                .collect(Collectors.toSet());
    }

    protected LinkedHashMap<String, Integer> getSamplesPosition(Integer fileId) {
        if (!samplesPositionMap.containsKey(fileId)) {
            synchronized (samplesPositionMap) {
                if (!samplesPositionMap.containsKey(fileId)) {
                    LinkedHashMap<String, Integer> samplesPosition = new LinkedHashMap<>();
                    for (Integer sampleId : studyConfiguration.getSamplesInFiles().get(fileId)) {
                        samplesPosition.put(studyConfiguration.getSampleIds().inverse().get(sampleId), samplesPosition.size());
                    }
                    samplesPositionMap.put(fileId, samplesPosition);
                }
            }
        }
        return samplesPositionMap.get(fileId);
    }

    public List<String> buildFormat(StudyConfiguration studyConfiguration) {
        List<String> format = new LinkedList<>();
        if (!excludeGenotypes) {
            format.add(VariantMerger.GT_KEY);
        }
        format.addAll(studyConfiguration.getAttributes().getAsStringList(VariantStorageManager.Options.EXTRA_GENOTYPE_FIELDS.key()));
        return format;
    }

    public boolean getExcludeGenotypes(StudyConfiguration studyConfiguration) {
        return studyConfiguration.getAttributes().getBoolean(VariantStorageManager.Options.EXCLUDE_GENOTYPES.key(),
                VariantStorageManager.Options.EXCLUDE_GENOTYPES.defaultValue());
    }
}
