USE [FEMPROBIEN];
GO
IF OBJECT_ID(N'dbo.tblCreditoSaldoHistorico', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tblCreditoSaldoHistorico (
        id_credito INT NOT NULL,
        periodo DATE NOT NULL,
        saldo DECIMAL(18, 2) NOT NULL CHECK (saldo >= 0),
        fecha_importacion DATETIME2 NOT NULL,
        CONSTRAINT PK_tblCreditoSaldoHistorico PRIMARY KEY (id_credito, periodo)
    );
END;
GO
