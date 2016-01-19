package org.opencb.opencga.catalog.db.mongodb.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.bson.Document;
import org.opencb.opencga.catalog.models.Individual;

import java.io.IOException;

/**
 * Created by pfurio on 19/01/16.
 */
public class IndividualConverter extends GenericConverter<Individual, Document> {

    public IndividualConverter() {
        objectReader = objectMapper.reader(Individual.class);
    }

    @Override
    public Individual convertToDataModelType(Document object) {
        Individual individual = null;
        try {
            individual = objectReader.readValue(objectWriter.writeValueAsString(object));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return individual;
    }

    @Override
    public Document convertToStorageType(Individual object) {
        Document document = null;
        try {
            document = Document.parse(objectWriter.writeValueAsString(object));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return document;
    }
}