-- Quem iniciou o projeto na propria agenda. E em nome dele que a OS e recebida na Frota
-- e, quando os projetos da OS terminam, despachada para o Patio.
ALTER TABLE agendamentos ADD COLUMN iniciado_por INTEGER REFERENCES usuarios (id);
