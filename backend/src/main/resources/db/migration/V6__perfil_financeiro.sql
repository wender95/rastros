-- Perfil Financeiro: ve o painel e cadastra usuarios (inclusive redefine senha).
-- Nao movimenta OS nem acessa agenda, produtividade ou relatorio.
INSERT INTO perfis (nome)
SELECT 'FINANCEIRO' WHERE NOT EXISTS (SELECT 1 FROM perfis WHERE nome = 'FINANCEIRO');
