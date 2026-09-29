# 9. Caminho para Produção

> Diagnóstico de onde o RastrOS está (atualizado em 29/09/2026) e o que falta para ele
> ser o sistema oficial da operação, no lugar da planilha. O [roadmap original](./08-roadmap.md)
> descreve a visão de produto; este documento trata de **colocar essa visão no ar e mantê-la
> no ar**.

---

## 9.1 O que "estar em produção" significa aqui

Não é só "o sistema abre no navegador". Um sistema está em produção quando:

1. **As pessoas dependem dele** — a planilha deixou de ser a fonte da verdade.
2. **Ele sobrevive a acidentes** — queda de energia, disco cheio, máquina roubada, um
   `DELETE` errado. Existe backup *fora da máquina* e alguém já testou restaurar.
3. **Ele é seguro o bastante para os dados que guarda** — senhas não trafegam em texto
   aberto, cada pessoa tem seu login, dados de desempenho de funcionários são tratados com
   cuidado (LGPD).
4. **Dá para mudar sem medo** — existe um jeito repetível de publicar uma versão nova e de
   voltar para a anterior se der errado.
5. **Alguém é responsável** — se cair às 7h30, todo mundo sabe quem liga e o que fazer.

---

## 9.2 Estado atual

O sistema está **em uso na empresa desde 21/09/2026** e, desde o fim de setembro, **na
internet**: roda no servidor da empresa e é publicado pelo **Cloudflare Tunnel** no domínio
próprio (`rastros.cloud`, um subdomínio por empresa), com HTTPS, sem abrir porta no roteador. As OS
chegam do ERP pela extensão do navegador, e a frota trabalha pela *Minha agenda* no celular.

### ✅ O que já está pronto

| Área | Situação |
| :--- | :--- |
| Funcionalidades | Rastreio de OS por setor, matriz de transição, Financeiro que conclui, pátio e prateleira; agenda com arrastar/redimensionar/trocar/recortar/desfazer; *Minha agenda* do adesivador; painel, produtividade e relatório por período |
| Regras no servidor | Permissões por perfil validadas na API; eventos imutáveis |
| Banco | Migrações Flyway V1–V20, backup automático antes de cada uma, *hash* das migrações publicadas conferido em teste; H2 e **PostgreSQL comprovado em teste** |
| Qualidade | 220 testes no backend, 30 no frontend; o build de produção falha se um teste falhar |
| Segurança | HTTPS pelo Cloudflare, nenhuma porta aberta; JWT com chave por instalação, BCrypt, senha provisória obrigatória, política de senha, freio contra adivinhar senha, CORS só do endereço da empresa, console H2 fechado; trava otimista (`@Version`) na movimentação de OS |
| Empacotamento | Um único `.jar` com API e tela; pasta portátil com Java embutido; pacote de atualização (parar, trocar o `app.jar`, ligar) com cópia do banco antes e volta automática se a versão nova não subir |
| Operação | No servidor da empresa, sobe com o Windows junto com o túnel (`servidor/SERVIDOR-INSTALAR.ps1`); backup diário, restauração por script, feriados cadastráveis |
| Entrada de dados | Extensão do navegador que importa a OS do ERP (sem duplicar); importador do Google Sheets; gerador de dados fictícios para teste, com limpeza |
| Documentação | README, especificação em `docs/` e guia de operação alinhados com a versão atual |

### ⚠️ Lacunas que continuam abertas

| # | Lacuna | Risco se ficar como está |
| :-- | :--- | :--- |
| L3 | **Backup fica na mesma máquina** do banco | Backup que morre junto com o servidor não é backup. |
| L5 | Banco em produção é **H2 em arquivo** | Funciona, mas PostgreSQL é melhor para BI, backup e suporte. |
| L10 | Sem monitoramento nem alerta | O sistema pode estar fora do ar e ninguém saber até alguém reclamar. |
| L11 | Decisões de negócio: suporte e substituto, quem vê a produtividade de quem | Sem isso, a operação depende de uma pessoa só. |
| L12 | *Agir como* do administrador ligado (fase de teste) | Quem tiver a senha do admin age por qualquer um; desligar ao fim da fase de teste (`PERSONIFICACAO=false`). |

Resolvidas desde a primeira versão deste documento: repositório remoto (L1 — GitHub
privado), HTTPS (L4 — Cloudflare), limite de tentativas de login (L6), trabalho não commitado (L2),
caminhos fixos nos scripts (L7 — agora `scripts/ambiente.ps1` acha o Java e o Maven),
dados reais no código (L8 — planilha por parâmetro, abas de teste anonimizadas, vendedores
vindos dos usuários) e documentação desatualizada (L9). O **histórico** do repositório ainda
contém os dados antigos: por isso a versão pública é publicada com histórico novo.

## 9.3 Roadmap

As fases são sequenciais: cada uma termina com um **critério de saída** verificável. As
estimativas assumem uma pessoa dedicando parte do tempo ao projeto.

```
Fase 0          Fase 1           Fase 2            Fase 3        Fase 4        Fase 5
Arrumar a  ──▶  Decisões com ──▶ Endurecer    ──▶  Piloto   ──▶  Virada   ──▶  Operação
casa            a diretoria      (segurança e      (2 sem.)      oficial       contínua
(1 sem.)        (1 reunião)      confiabilidade)                               e evolução
                                 (2–3 sem.)
```

### Fase 0 — Arrumar a casa · ~1 semana

Objetivo: o código fica seguro, organizado e apresentável.

- [x] Terminar ou separar o trabalho em andamento (`frontend/src/agenda/`, vitest) e commitar.
- [x] Criar repositório **privado** no GitHub e fazer `push` (resolve L1).
- [ ] Criar uma tag `v1.0.0` marcando a primeira versão candidata a produção.
- [x] Tirar caminhos fixos dos scripts: procurar `java`/`mvn`/`node` pelo `PATH` ou por
      `JAVA_HOME`, e só cair num caminho conhecido como última opção (L7). A documentação já
      usa caminhos relativos à pasta do projeto.
- [x] Atualizar README, `COMO-EXECUTAR` e o roadmap antigo (L9).
- [x] Anonimizar as planilhas de teste (trocar nomes de pessoas e clientes) e mover o ID
      da planilha para parâmetro obrigatório ou variável de ambiente (L8).

**Critério de saída:** um clone limpo do repositório, numa máquina que nunca viu o
projeto, gera o pacote seguindo só o README.

### Fase 1 — Decisões com a diretoria · 1 reunião

Estas decisões não são técnicas; são do negócio. Levar as opções já analisadas:

| Decisão | Opções | Recomendação |
| :--- | :--- | :--- |
| **Onde o sistema roda** | (a) Um computador/servidor na empresa · (b) Nuvem (VPS ~R$ 30–80/mês) | ✅ **Decidido: (a)**, o servidor da empresa — custo zero, os scripts já eram para Windows. Migrar para nuvem depois é possível. |
| **Acesso de fora da empresa** | Só rede interna · Também pela internet (celular da frota, vendedor em visita) | ✅ **Decidido: pela internet**, com o Cloudflare Tunnel no domínio `rastros.cloud` (HTTPS, sem porta aberta, sem IP fixo). |
| **Quem dá suporte** | Você · TI da empresa · fornecedor | Definir um responsável principal e um substituto. |
| **Visibilidade da produtividade** | Quem pode ver os números de cada adesivador | Hoje: Vendedor, Diretoria e Admin. Confirmar e **comunicar aos funcionários** (LGPD: transparência sobre dados de desempenho). |
| **Data da virada** | — | Escolher uma semana de movimento normal, nunca véspera de feriado ou pico. |

**Critério de saída:** decisões registradas por escrito (e-mail ou ata) com o aceite do diretor.

### Fase 2 — Endurecer · ~2–3 semanas

Objetivo: o sistema aguenta o dia a dia real e acidentes.

**Infraestrutura**
- [x] Preparar a máquina servidora: o servidor da empresa, sem uso pessoal (nobreak se possível).
- [ ] Instalar PostgreSQL e apontar o sistema para ele (L5). Os testes já comprovam que
      funciona; é configuração via `DB_URL` / `DB_USER` / `DB_PASSWORD`.
- [ ] Guardar as variáveis de produção (`DB_PASSWORD`, `JWT_SECRET`) fora do código.

**Segurança**
- [x] HTTPS (L4): o Cloudflare Tunnel publica o sistema com o certificado do Cloudflare.
- [x] Limitar tentativas de login por usuário/IP (L6), com o IP real vindo do Cloudflare.
- [ ] Opcional: Cloudflare Access (código por e-mail antes de abrir o sistema), com liberação
      de `/api/` para a extensão do ERP.
- [ ] Guardar o token de forma menos exposta ou reduzir o tempo de expiração (hoje 12h em
      `localStorage`) — avaliar junto com o HTTPS.
- [ ] Criar os usuários reais (todos começam com senha provisória, que já é obrigatória trocar).
- [x] Confirmar que `CARGA_DEMO=false` e `H2_CONSOLE=false` em produção (`servidor/SERVIDOR-INICIAR.cmd`).

**Confiabilidade**
- [ ] Backup **fora da máquina** (L3): copiar o backup diário para um NAS, outro computador
      ou nuvem (Google Drive/OneDrive da empresa). Regra 3-2-1: 3 cópias, 2 mídias, 1 fora.
- [ ] **Teste de restauração**: restaurar o backup numa máquina limpa e conferir os dados.
      Backup nunca testado não conta.
- [ ] Monitoramento simples (L10): um serviço gratuito que acessa `/api/health` a cada
      minuto e avisa por e-mail/WhatsApp se cair. Rotação dos logs para não encher o disco.
- [x] Escrever o **runbook**: `servidor/SERVIDOR-LEIA-ME.txt` (instalar, túnel, reiniciar, logs,
      atualizar, problemas comuns). Falta só "quem chamar".

**Critério de saída:** simulação de desastre concluída — desligar o servidor no meio do
uso, religar e confirmar que voltou sozinho; restaurar o backup de ontem em outra máquina.

### Fase 3 — Piloto · ~2 semanas

Objetivo: descobrir os problemas reais com pouca gente, antes de envolver todo mundo.

- [ ] Escolher o escopo: por exemplo, **agenda dos adesivadores + 1 ou 2 setores** de produção.
- [ ] Treinar os participantes (15–20 min cada, no posto de trabalho, não em sala).
- [ ] Rodar **em paralelo com a planilha**: a planilha continua oficial; o sistema é conferido
      contra ela todo fim de dia.
- [ ] Canal único de feedback (um grupo, uma planilha de ocorrências) e reunião curta semanal.
- [ ] Medir adoção: quantas OS foram registradas no sistema vs. na planilha, quantos
      recebimentos/despachos por dia, quantos erros reportados.

**Critério de saída:** duas semanas sem erro que perca dado ou bloqueie o trabalho, e os
participantes preferindo o sistema à planilha.

### Fase 4 — Virada oficial · ~1 semana

- [ ] Backup final e importação final da planilha (depois disso, **não reimportar**: a chave
      de importação é a posição na grade e geraria duplicatas).
- [ ] Planilha passa a **somente leitura** — um único lugar para lançar dados.
- [ ] Treinar os demais setores.
- [ ] Primeira semana com você disponível para suporte presencial.
- [ ] Comunicado da diretoria: a partir da data X, o registro oficial é o RastrOS.

**Critério de saída:** planilha congelada e um mês de operação só no sistema.

### Fase 5 — Operação contínua e evolução

**Manter**
- Processo de versão: branch → testes passando → tag (`v1.1.0`) → backup → publicar →
  conferir → (se der errado) voltar para a tag anterior.
- CI no GitHub Actions: rodar os testes a cada `push`.
- `CHANGELOG.md` com o que mudou em cada versão, em linguagem de usuário.
- Revisão mensal: backups conferidos, disco, atualizações de segurança de Java e Windows.

**Evoluir** (do roadmap de produto, após estabilizar)
- Dashboards de BI (Metabase/Power BI) lendo o PostgreSQL.
- Motivos padronizados para pausas longas.

---

## 9.4 Riscos e como estão tratados

| Risco | Probabilidade | Mitigação |
| :--- | :--- | :--- |
| Resistência da equipe ("a planilha funcionava") | Alta | Piloto com quem tem boa vontade; mostrar ganhos concretos (saber onde está a OS sem perguntar); apoio explícito da diretoria. |
| Perda de dados | Baixa, com a Fase 2 | Backup automático + cópia externa + restauração testada; backup automático antes de cada migração de banco (já existe). |
| Dependência de uma só pessoa | Alta | Runbook, documentação, código no GitHub, um substituto treinado para o básico (reiniciar, restaurar). |
| Servidor desligado por engano | Média | Máquina dedicada e identificada, sobe sozinho com o Windows (já existe), monitoramento com alerta. |
| Exposição de dados de desempenho | Média | Perfis de acesso já limitam quem vê; comunicar a equipe; repositório privado e dados de teste anonimizados. |

---

## 9.5 Resumo para a diretoria

**Problema.** A localização das OS e a agenda dos adesivadores dependem de planilha e de
comunicação verbal. Não se sabe com precisão onde cada OS está, quanto tempo ficou parada na
fila versus em trabalho, nem onde estão os gargalos.

**O que foi construído.** Um sistema web interno que registra cada movimentação de OS entre
setores, substitui a planilha da agenda com vínculo direto à OS e calcula a produtividade
semanal — tudo com controle de acesso por perfil e histórico que não pode ser apagado.

**Onde estamos.** As funcionalidades estão prontas, testadas e em uso na empresa desde
21/09/2026, no servidor da empresa e acessíveis pela internet com HTTPS. Falta o backup fora
do servidor e o monitoramento com alerta.

**Custo.** Software 100% gratuito (Kotlin, Spring, React, PostgreSQL) e Cloudflare no plano
gratuito. O único custo é o domínio `rastros.cloud`; o servidor é o da empresa.

**O que é preciso da diretoria.**
1. ~~Aprovar onde o sistema vai rodar~~ — no servidor da empresa, publicado pela internet.
2. Indicar um responsável substituto.
3. Escolher os participantes e a data do piloto.
4. Patrocinar a mudança junto à equipe na virada.

**Prazo estimado.** De 6 a 8 semanas até a virada oficial.
