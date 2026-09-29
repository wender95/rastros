-- Duas pessoas no mesmo fluxo ao mesmo tempo: a versao faz so a primeira gravar.
ALTER TABLE fluxos_os ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;

-- Numero de OS unico entre as que valem. A cancelada fica com nulo, que o UNIQUE aceita
-- repetido, e o numero pode ser aberto de novo.
ALTER TABLE ordens_servico ADD COLUMN numero_ativo VARCHAR(50);

-- Se ja houver OS abertas repetidas, a mais antiga fica com o numero e as outras seguem
-- como estao (sem numero_ativo) - a migracao nao pode derrubar o servidor por isso.
UPDATE ordens_servico o
   SET numero_ativo = LOWER(TRIM(o.numero_os_erp))
 WHERE o.cancelada = FALSE
   AND o.id = (SELECT MIN(d.id) FROM ordens_servico d
                WHERE d.cancelada = FALSE
                  AND LOWER(TRIM(d.numero_os_erp)) = LOWER(TRIM(o.numero_os_erp)));

ALTER TABLE ordens_servico ADD CONSTRAINT uk_os_numero_ativo UNIQUE (numero_ativo);

-- Vai no token: trocar ou redefinir a senha incrementa e derruba as sessoes antigas.
ALTER TABLE usuarios ADD COLUMN versao_sessao INTEGER NOT NULL DEFAULT 0;
