-- Rename built-in contacts skill capability: contacts.read → users.read
-- The skill was renamed from "contacts" to "users" to avoid confusion with google_contacts.
UPDATE `capabilityGrant` SET `capability` = 'users.read' WHERE `capability` = 'contacts.read';
