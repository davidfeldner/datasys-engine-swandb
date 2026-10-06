CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG, logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'logs/engine.log';
SELECT * FROM logs WHERE sessionId = '74bd7c28-ffbb-4941-9b4e-659cff26a8d7';
SELECT * FROM logs WHERE statementNumber = 7;
SELECT * FROM logs WHERE logLevel = 'ERROR';