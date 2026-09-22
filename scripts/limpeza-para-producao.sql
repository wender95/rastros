-- Preparacao para o teste em producao na empresa (21/09/2026), a pedido:
--  * apaga as 9 OS de demonstracao e as movimentacoes delas;
--  * zera a agenda - ela volta da planilha so de hoje em diante (colocar-em-producao.ps1);
--  * zera a pilha do desfazer (os retratos recriariam carros apagados);
--  * desativa os usuarios de demonstracao. Ficam ativos o admin@ostracker.com e as
--    contas reais criadas no sistema.
-- Rodado com a aplicacao parada, depois de um backup (backend/backups/*-antes-da-producao.zip).

DELETE FROM historico_agenda;
DELETE FROM agendamentos;
DELETE FROM eventos_movimentacao;
DELETE FROM fluxos_os;
DELETE FROM ordens_servico;

UPDATE usuarios SET ativo = FALSE
 WHERE email IN (
     'vendedor@ostracker.com', 'vendedor2@ostracker.com', 'diretoria@ostracker.com',
     'criacao@ostracker.com', 'impressao@ostracker.com', 'recorte@ostracker.com',
     'preparacao@ostracker.com', 'acabamento@ostracker.com', 'frota@ostracker.com'
 );
