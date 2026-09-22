-- O Hibernate criou as colunas de enum como tipo ENUM nativo do H2. Isso nao existe no
-- PostgreSQL e trava a inclusao de um valor novo (um status a mais, por exemplo): o
-- banco recusaria o texto. Passam a ser texto simples, como ja sao no V1.
--
-- Num banco criado pelo V1 as colunas ja sao VARCHAR e isto nao muda nada.

ALTER TABLE perfis ALTER COLUMN nome SET DATA TYPE VARCHAR(50);
ALTER TABLE setores ALTER COLUMN nome SET DATA TYPE VARCHAR(50);
ALTER TABLE transicoes_permitidas ALTER COLUMN setor_destino SET DATA TYPE VARCHAR(50);
ALTER TABLE transicoes_permitidas ALTER COLUMN setor_origem SET DATA TYPE VARCHAR(50);
ALTER TABLE fluxos_os ALTER COLUMN status_atual SET DATA TYPE VARCHAR(50);
ALTER TABLE eventos_movimentacao ALTER COLUMN tipo_evento SET DATA TYPE VARCHAR(30);
ALTER TABLE adesivadores ALTER COLUMN tipo SET DATA TYPE VARCHAR(20);
ALTER TABLE agendamentos ALTER COLUMN status SET DATA TYPE VARCHAR(20);
ALTER TABLE agendamentos ALTER COLUMN tipo SET DATA TYPE VARCHAR(20);

-- Registros anteriores a coluna de tipo eram todos servico (antes feito na partida da
-- aplicacao, por AjusteFaixasDoDia).
UPDATE agendamentos SET tipo = 'SERVICO' WHERE tipo IS NULL;
