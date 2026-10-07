USE FEMPROBIEN;
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID('dbo.tblFemprobienConfiguracionCredito','U') IS NULL
BEGIN
 CREATE TABLE dbo.tblFemprobienConfiguracionCredito (
  id INT NOT NULL CONSTRAINT PK_FemprobienConfiguracionCredito PRIMARY KEY,
  tasa_mensual DECIMAL(9,6) NOT NULL,
  modificado_por NVARCHAR(100) NOT NULL,
  fecha_modificacion DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
  CONSTRAINT CK_FemprobienConfiguracionId CHECK (id=1),
  CONSTRAINT CK_FemprobienConfiguracionTasa CHECK (tasa_mensual BETWEEN 0 AND 100)
 );
END;
IF NOT EXISTS (SELECT 1 FROM dbo.tblFemprobienConfiguracionCredito WHERE id=1)
 INSERT INTO dbo.tblFemprobienConfiguracionCredito(id,tasa_mensual,modificado_por) VALUES(1,1.35,'CONFIGURACION_INICIAL');
IF OBJECT_ID('dbo.tblFemprobienTasaCreditoAuditoria','U') IS NULL
 CREATE TABLE dbo.tblFemprobienTasaCreditoAuditoria (
  id BIGINT IDENTITY(1,1) PRIMARY KEY,
  tasa_anterior DECIMAL(9,6) NOT NULL,
  tasa_nueva DECIMAL(9,6) NOT NULL,
  realizado_por NVARCHAR(100) NOT NULL,
  fecha_registro DATETIME2 NOT NULL DEFAULT SYSDATETIME()
 );
IF COL_LENGTH('dbo.tblAsociadoCredito','tasa_interes_mensual') IS NULL
 ALTER TABLE dbo.tblAsociadoCredito ADD tasa_interes_mensual DECIMAL(9,6) NULL;
IF COL_LENGTH('dbo.tblAsociadoCredito','frecuencia_pago') IS NULL
 ALTER TABLE dbo.tblAsociadoCredito ADD frecuencia_pago VARCHAR(10) NULL;
COMMIT;
GO