-- Um funcionario pode ser de mais de um setor (ex.: Recorte e Frota). O setor principal
-- continua em usuarios.setor_id; aqui ficam os outros. Em "Meu setor" a pessoa escolhe
-- em qual esta trabalhando.
CREATE TABLE usuario_setores (
    usuario_id INTEGER NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    setor_id   INTEGER NOT NULL REFERENCES setores (id),
    PRIMARY KEY (usuario_id, setor_id)
);
