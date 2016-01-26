/*
 * Copyright 2015 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.catalog.db.mongodb;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.models.Study;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by pfurio on 19/01/16.
 */
public class CatalogMongoStudyDBAdaptorTest {

    static CatalogMongoDBAdaptorFactory dbAdaptorFactory;

    @Rule
    public ExpectedException thrown = ExpectedException.none();
    private CatalogMongoStudyDBAdaptor catalogStudyDBAdaptor;

    @AfterClass
    public static void afterClass() {
        CatalogMongoDBAdaptorTest.afterClass();
    }

    @Before
    public void before() throws IOException, CatalogDBException {
        CatalogMongoDBAdaptorTest dbAdaptorTest = new CatalogMongoDBAdaptorTest();
        dbAdaptorTest.before();

        dbAdaptorFactory = CatalogMongoDBAdaptorTest.catalogDBAdaptor;
        catalogStudyDBAdaptor = dbAdaptorFactory.getCatalogStudyDBAdaptor();
    }

    @Test
    public void updateDiskUsage() throws Exception {
        catalogStudyDBAdaptor.updateDiskUsage(5, 100);
        assertEquals(2100, catalogStudyDBAdaptor.getStudy(5, null).getResult().get(0).getDiskUsage());
        catalogStudyDBAdaptor.updateDiskUsage(5, -200);
        assertEquals(1900, catalogStudyDBAdaptor.getStudy(5, null).getResult().get(0).getDiskUsage());
    }

    /***
     * The test will check whether it is possible to create a new study using an alias that is already being used, but on a different
     * project.
     */
    @Test
    public void createStudySameAliasDifferentProject() throws CatalogDBException {
        QueryResult<Study> ph1 = catalogStudyDBAdaptor.createStudy(1, new Study("Phase 1", "ph1", Study.Type.CASE_CONTROL, "", "", null),
                null);
        assertTrue("It is impossible creating an study with an existing alias on a different project.", ph1.getNumResults() == 1);
    }


}
