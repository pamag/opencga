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

package org.opencb.opencga.catalog.db.api2;

import org.apache.commons.lang3.StringUtils;
import org.opencb.commons.datastore.core.*;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.models.Session;
import org.opencb.opencga.catalog.models.User;

import static org.opencb.commons.datastore.core.QueryParam.Type.TEXT_ARRAY;

/**
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public interface CatalogUserDBAdaptor extends CatalogDBAdaptor<User> {

    enum QueryParams implements QueryParam {
        ID("id", TEXT_ARRAY, ""),
        NAME("name", TEXT_ARRAY, ""),
        EMAIL("email", TEXT_ARRAY, ""),
        ORGANIZATION("organization", TEXT_ARRAY, ""),
        STATUS("status", TEXT_ARRAY, ""),
        LAST_ACTIVITY("lastActivity", TEXT_ARRAY, "");

        private final String key;
        private Type type;
        private String description;

        QueryParams(String key, Type type, String description) {
            this.key = key;
            this.type = type;
            this.description = description;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Type type() {
            return type;
        }

        @Override
        public String description() {
            return description;
        }
    }

    /**
     * User methods
     * ***************************
     */
    boolean checkUserCredentials(String userId, String sessionId);

    @Deprecated
    default boolean userExists(String userId) {
        return count(new Query(QueryParams.ID.key(), userId)).getResult().get(0) > 0;
    }

    default void checkUserExists(String userId) throws CatalogDBException {
        if (StringUtils.isEmpty(userId)) {
            throw new CatalogDBException("UserId not valid: " + userId);
        }

        if (count(new Query("id", userId)).getResult().get(0) == 0) {
            throw new CatalogDBException("UserId does not exist: " + userId);
        }
    }

    QueryResult<User> createUser(String userId, String userName, String email, String password, String organization, QueryOptions options)
            throws CatalogDBException;

    QueryResult<User> insertUser(User user, QueryOptions options) throws CatalogDBException;


    QueryResult<User> getUser(String userId, QueryOptions options, String lastActivity) throws CatalogDBException;


    QueryResult<User> modifyUser(String userId, ObjectMap parameters) throws CatalogDBException;

    QueryResult<Integer> deleteUser(String userId) throws CatalogDBException;


    QueryResult<ObjectMap> login(String userId, String password, Session session) throws CatalogDBException;

    QueryResult<Session> addSession(String userId, Session session) throws CatalogDBException;

    QueryResult logout(String userId, String sessionId) throws CatalogDBException;

    QueryResult<ObjectMap> loginAsAnonymous(Session session) throws CatalogDBException;

    QueryResult logoutAnonymous(String sessionId) throws CatalogDBException;


    QueryResult changePassword(String userId, String oldPassword, String newPassword) throws CatalogDBException;

    void updateUserLastActivity(String userId) throws CatalogDBException;


    QueryResult resetPassword(String userId, String email, String newCryptPass) throws CatalogDBException;

    QueryResult<Session> getSession(String userId, String sessionId) throws CatalogDBException;

    String getUserIdBySessionId(String sessionId);


    /**
     * Project methods moved to ProjectDBAdaptor
     * ***************************
     */

//    QueryResult<Project> createProject(String userId, Project project, QueryOptions options) throws CatalogDBException;
//
//    boolean projectExists(int projectId);
//
//    QueryResult<Project> getAllProjects(String userId, QueryOptions options) throws CatalogDBException;
//
//    QueryResult<Project> getProject(int project, QueryOptions options) throws CatalogDBException;
//
//    QueryResult<Integer> deleteProject(int projectId) throws CatalogDBException;
//
//    QueryResult renameProjectAlias(int projectId, String newProjectName) throws CatalogDBException;
//
//    QueryResult<Project> modifyProject(int projectId, ObjectMap parameters) throws CatalogDBException;
//
//    int getProjectId(String userId, String projectAlias) throws CatalogDBException;
//
//    String getProjectOwnerId(int projectId) throws CatalogDBException;
//
//    QueryResult<AclEntry> getProjectAcl(int projectId, String userId) throws CatalogDBException;
//
//    QueryResult setProjectAcl(int projectId, AclEntry newAcl) throws CatalogDBException;

}
