-- Quem registrou cada acao do projeto: o proprio adesivador, na Minha agenda, ou alguem
-- com a permissao de agir pelo painel. O relatorio mostra o nome ao passar o mouse no
-- horario. (iniciado_por continua sendo o adesivador: e em nome dele que a OS anda.)
ALTER TABLE agendamentos ADD COLUMN inicio_registrado_por INTEGER REFERENCES usuarios (id) ON DELETE SET NULL;
ALTER TABLE agendamentos ADD COLUMN conclusao_registrada_por INTEGER REFERENCES usuarios (id) ON DELETE SET NULL;
ALTER TABLE pausas_projeto ADD COLUMN retomado_por INTEGER REFERENCES usuarios (id) ON DELETE SET NULL;
