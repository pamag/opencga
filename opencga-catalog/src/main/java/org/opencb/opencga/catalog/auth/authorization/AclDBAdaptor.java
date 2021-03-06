package org.opencb.opencga.catalog.auth.authorization;

import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.models.acls.permissions.AbstractAclEntry;

import java.util.List;

/**
 * Created by pfurio on 29/07/16.
 */
public interface AclDBAdaptor<T extends AbstractAclEntry> {

    /**
     * Register the Acl given in the resource.
     *
     * @param resourceId id of the study, file, sample... where the Acl will be registered.
     * @param acl Acl object to be registered.
     * @return the same Acl once it is properly registered.
     * @throws CatalogDBException if any problem occurs during the insertion.
     */
    T createAcl(long resourceId, T acl) throws CatalogDBException;

    /**
     * Retrieve the list of Acls for the list of members in the resource given.
     *
     * @param resourceId id of the study, file, sample... where the Acl will be looked for.
     * @param members members for whom the Acls will be obtained.
     * @return the list of Acls defined for the members.
     */
    List<T> getAcl(long resourceId, List<String> members);

    /**
     * Remove the existing Acl for the member.
     *
     * @param resourceId id of the study, file, sample... where the Acl will be looked for.
     * @param member member for whom the Acl will be removed.
     * @throws CatalogDBException if any problem occurs during the removal.
     */
    void removeAcl(long resourceId, String member) throws CatalogDBException;

    /**
     * Remove all the Acls defined for the member in the resource for the study.
     *
     * @param studyId study id where the Acls will be removed from.
     * @param member member from whom the Acls will be removed.
     * @throws CatalogDBException if any problem occurs during the removal.
     */
    void removeAclsFromStudy(long studyId, String member) throws CatalogDBException;

    /**
     * Set a new set of permissions for the member.
     *
     * @param resourceId id of the study, file, sample... where the Acl will be looked for.
     * @param member member for whom the permissions will be set.
     * @param permissions list of permissions.
     * @return the Acl with the changes applied.
     * @throws CatalogDBException if any problem occurs during the change of permissions.
     */
    T setAclsToMember(long resourceId, String member, List<String> permissions) throws CatalogDBException;

    /**
     * Add new permissions for the member.
     *
     * @param resourceId id of the study, file, sample... where the Acl will be looked for.
     * @param member member for whom the permissions will be added.
     * @param permissions list of permissions.
     * @return the Acl with the changes applied.
     * @throws CatalogDBException if any problem occurs during the change of permissions.
     */
    T addAclsToMember(long resourceId, String member, List<String> permissions) throws CatalogDBException;

    /**
     * Remove some member permissions.
     *
     * @param resourceId id of the study, file, sample... where the Acl will be looked for.
     * @param member member for whom the permissions will be removed.
     * @param permissions list of permissions to be removed.
     * @return the Acl with the changes applied.
     * @throws CatalogDBException if any problem occurs during the change of permissions.
     */
    T removeAclsFromMember(long resourceId, String member, List<String> permissions) throws CatalogDBException;
}
