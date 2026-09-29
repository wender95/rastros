-- O que a OS manda fazer, como o ERP escreve ("KIT ADESIVO POSTES - 6 UNIDADES").
-- Vem junto quando a OS entra pela extensao do ERP; nas antigas fica vazio.
ALTER TABLE ordens_servico ADD COLUMN servico VARCHAR(200);
