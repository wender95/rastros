-- Secoes por pessoa: cada perfil traz as suas prontas e o cadastro acrescenta (liberada =
-- verdadeiro) ou tira (falso) secoes de cada um. O perfil Personalizado comeca sem nenhuma
-- (ex.: a TV que so mostra a agenda dos adesivadores).
INSERT INTO perfis (nome)
SELECT 'PERSONALIZADO' WHERE NOT EXISTS (SELECT 1 FROM perfis WHERE nome = 'PERSONALIZADO');

CREATE TABLE usuario_secoes (
    usuario_id INTEGER     NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    secao      VARCHAR(40) NOT NULL,
    liberada   BOOLEAN     NOT NULL,
    PRIMARY KEY (usuario_id, secao)
);

-- Coluna Noturno: os adesivadores atribuidos a cada servico dela.
CREATE TABLE agendamento_atribuidos (
    agendamento_id BIGINT  NOT NULL REFERENCES agendamentos (id) ON DELETE CASCADE,
    adesivador_id  INTEGER NOT NULL REFERENCES adesivadores (id) ON DELETE CASCADE,
    PRIMARY KEY (agendamento_id, adesivador_id)
);

-- A coluna Encaixe sai da agenda. Sem nenhum servico, sai de vez; com servicos, fica so
-- no historico: aparece apenas nas semanas em que ja tem servico, como um adesivador
-- removido.
DELETE FROM adesivadores
WHERE tipo = 'ENCAIXE' AND id NOT IN (SELECT DISTINCT adesivador_id FROM agendamentos);
UPDATE adesivadores SET ativo = FALSE WHERE tipo = 'ENCAIXE';
