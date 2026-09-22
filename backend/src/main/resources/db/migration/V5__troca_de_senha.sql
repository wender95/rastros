-- Senha provisoria: quem esta com ela so consegue trocar a senha, e mais nada.
-- Vale para a senha de demonstracao (123456) e para a senha inicial que o
-- administrador define ao criar ou redefinir um usuario.
ALTER TABLE usuarios ADD COLUMN trocar_senha BOOLEAN DEFAULT FALSE NOT NULL;
