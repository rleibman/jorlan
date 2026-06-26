-- Add authorship + review fields to skillVersion for declarative skill lifecycle
ALTER TABLE skillVersion
  ADD COLUMN createdBy  BIGINT NULL,
  ADD COLUMN reviewNote VARCHAR(1000) NULL,
  ADD CONSTRAINT fk_sv_user FOREIGN KEY (createdBy) REFERENCES user(id);
