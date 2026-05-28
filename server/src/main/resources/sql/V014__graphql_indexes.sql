-- Phase 6: indexes supporting GraphQL list queries

-- userRole: composite index for roles-by-user lookups (roles(userId) GraphQL query)
ALTER TABLE `userRole`
    ADD INDEX `idx_user_role_user_role` (`userId`, `roleId`);

-- permission: single-column indexes for searchPermissions filtering by userId or roleId
-- (The composite indexes from V010 cover (userId, resource, action) and (roleId, resource, action);
--  these narrower indexes speed up unfiltered userId/roleId list queries from the GraphQL API.)
ALTER TABLE `permission`
    ADD INDEX `idx_perm_user` (`userId`),
    ADD INDEX `idx_perm_role` (`roleId`);
