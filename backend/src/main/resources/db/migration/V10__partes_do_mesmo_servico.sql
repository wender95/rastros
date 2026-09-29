-- Um servico pode ser feito em pedacos separados na agenda (uma parte numa semana, outra
-- na seguinte). As partes sao o MESMO servico: dividem nome, vendedor, OS, observacao e
-- estado; cada uma tem so o seu lugar e o seu tamanho.
--
-- grupo_id aponta para a parte mais antiga do conjunto. Nulo = servico de uma parte so.
ALTER TABLE agendamentos ADD COLUMN grupo_id BIGINT;

CREATE INDEX idx_agendamento_grupo ON agendamentos (grupo_id);
