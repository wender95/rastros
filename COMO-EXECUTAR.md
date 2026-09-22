# Como executar o OS Tracker

Guia de operação: instalar, rodar, testar, fazer backup e entender cada tela e regra. A
visão geral do projeto está no [README](./README.md) e a especificação em [`docs/`](./docs/).

Os comandos abaixo partem da **pasta do projeto** (onde está este arquivo), num PowerShell.

## Stack

| Camada | Tecnologia |
| :--- | :--- |
| Backend | Kotlin 1.9 + Spring Boot 3.3 (Web, Security, Data JPA, Validation) |
| Autenticação | JWT (HS256) + BCrypt, validação de permissões no servidor |
| Banco | H2 em arquivo (`backend/data/`) por padrão; PostgreSQL por variável de ambiente |
| Schema | Flyway (`backend/src/main/resources/db/migration`) |
| Frontend | React 18 + TypeScript + Vite + React Router |
| Testes | JUnit 5 + H2 em memória + PostgreSQL embutido (backend); Vitest (frontend) |
| Build | Maven (o jar leva a tela junto) |

## Pré-requisitos

* JDK 21 ou mais novo
* Node.js 20+
* Maven 3.9 (só para gerar o pacote e desenvolver)

Nenhum script tem caminho fixo de máquina: todos carregam [`scripts/ambiente.ps1`](scripts/ambiente.ps1),
que acha o Java pelo `JAVA_HOME`, pelas instalações comuns de JDK 21+ (Adoptium, Oracle,
Microsoft) ou pelo `PATH`; o Maven pelo `MAVEN_HOME`, por uma pasta `.tools\apache-maven-*`
ao lado do projeto ou pelo `PATH`; e o Node em `Program Files\nodejs`. Assim eles funcionam
também em terminais abertos antes de instalar algo e na tarefa agendada do Windows.

## Produção: um endereço só

A API e a tela saem num **único jar** e ficam em <http://localhost:8080>, sem o servidor
do Vite.

### Em um passo

Com o pacote gerado, num PowerShell **aberto como administrador**:

```bash
powershell -ExecutionPolicy Bypass -File .\colocar-em-producao.ps1
```

Ele para o sistema que estiver rodando por fora, libera a porta 8080 no firewall,
registra o sistema para subir com o Windows, importa da planilha a agenda **de hoje em
diante** (pede a senha do admin) e mostra os endereços para a equipe. Pode rodar de novo
sem problema. `-SemImportar` pula a importação.

> O IP desta máquina pode mudar se o roteador distribuir endereços automaticamente. O
> endereço pelo nome (`http://NOME-DO-PC:8080`) não muda; para o IP também não mudar,
> reserve-o no roteador.

Os passos abaixo são o mesmo processo, separado.

**1. Gerar o pacote** (build da tela + testes do backend + jar):

```bash
powershell -ExecutionPolicy Bypass -File .\gerar-pacote.ps1
```

**2. Subir:**

```bash
powershell -ExecutionPolicy Bypass -File .\iniciar-producao.ps1
```

**3. (Opcional) Subir sozinho com o Windows.** Num PowerShell **aberto como
administrador**:

```bash
powershell -ExecutionPolicy Bypass -File .\instalar-servico.ps1
```

Isso registra a tarefa agendada **OS Tracker**: roda o `iniciar-producao.ps1` na partida
do Windows, com a conta do sistema, e reinicia a aplicação se ela cair (até 3 vezes, de
minuto em minuto). Para desfazer, rode o mesmo script com `-Remover`. Os logs ficam em
`backend\logs\ostracker.log`.

Para **outras máquinas da rede** abrirem o sistema, libere a porta no firewall (também como
administrador):

```bash
New-NetFirewallRule -DisplayName 'OS Tracker' -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

> A tarefa e o `iniciar-backend.ps1` usam a mesma porta 8080: com a tarefa registrada, não
> suba o backend de desenvolvimento ao mesmo tempo.

## Desenvolvimento: tela com recarga instantânea

Dois processos, cada um na sua aba de terminal. **Aba 1 — backend:**

```bash
powershell -ExecutionPolicy Bypass -File .\iniciar-backend.ps1
```

**Aba 2 — frontend:**

```bash
powershell -ExecutionPolicy Bypass -File .\iniciar-frontend.ps1
```

Depois abra <http://localhost:5173>. O Vite faz proxy de `/api` para a porta 8080.

> **Suba sempre o backend de dentro de `backend\`** (os scripts já fazem isso). O caminho
> do banco H2 é relativo: rodar o jar de outra pasta cria um banco vazio lá e a agenda
> "some".

Para recompilar o backend depois de mexer no código Kotlin (sem testes):

```bash
cd backend; mvn -DskipTests package
```

## Testes

```bash
cd backend; mvn test
```

```bash
cd frontend
```

```bash
npm test
```

O `gerar-pacote.ps1` roda os dois antes de montar o jar. O que está coberto:

| Suíte | O que garante |
| :--- | :--- |
| `PlanilhaGabaritoTest` | importa as abas reais de agosto e setembro e confere o score de **cada semana, de cada adesivador**, com a linha `TOTAL SCORE` da própria planilha |
| `AgendaServiceTest`, `ReempilharTest` | horário exato, almoço e sexta 17h protegidos, empurrar os de baixo, trocar arrastando (serviços de tamanhos diferentes), borda de cima, carga que nunca passa da capacidade, semana cortada no mês, desfazer; salvar sem mudar nada não mexe em ninguém |
| `MinhaAgendaTest`, `ColunasAgendaTest` | a agenda de cada adesivador (receber, entregar no Pátio, devolver) e o modo edição das colunas |
| `PainelTest`, `FeriadoTest`, `HorarioComercialTest` | painel por período; cadastro de feriados; o relógio das OS só conta o expediente (44h por semana) |
| `ExclusaoUsuarioTest`, `GeradorFicticioTest` | excluir só quem não tem histórico; o gerador de dados fictícios não sobrepõe carros e a limpeza tira só o que gerou |
| `ImportadorAgendaTest` | dia importado cabe nas 9h, sem sobreposição; reimportar é recusado; o reparo só completa dias ausentes |
| `PostgresTest` | a aplicação inteira sobre um **PostgreSQL 14 de verdade**, criado pelas migrações |
| `AcessosTest` | cada perfil contra cada área, por HTTP: só entra onde a tabela de perfis deixa |
| `ProdutividadeTest` | OS abertas por quem abriu; processadas e tempo no setor por quem recebeu; devolvidas; concluídas no Financeiro |
| `MovimentacaoTest` | receber, devolver e despachar na tela do setor, só pelos destinos da matriz; o Financeiro recebe e é o único que conclui |
| `AutenticacaoTest` | senha provisória bloqueia tudo menos a troca; política de senha; chave JWT por instalação |
| `BackupServiceTest` | o backup restaura num banco vazio com os mesmos dados |
| `ConsultasTest` | a semana da agenda e o relatório fazem um número fixo de consultas SQL (sem N+1) |
| `regua.test.ts`, `periodos.test.ts`, `duracao.test.ts` (Vitest) | as contas da grade no navegador batem com as do servidor; semanas cortadas no mês; tempo em dias úteis |

São **126 testes no backend e 22 no frontend**.

Os testes rodam em bancos próprios (H2 em memória e PostgreSQL temporário): **nunca tocam
o banco da empresa**.

## Perfis e acessos

Cada perfil vê só o que é dele. A regra vale **no servidor, rota por rota** — esconder o
menu é só conforto; uma chamada direta à API de uma área que não é sua recebe 403.

| Perfil | Vê e usa |
| :--- | :--- |
| **Administrador** | tudo: painel, agenda (e editar os adesivadores), pátio e prateleira, produtividade, relatório, consultar e abrir OS, administração (usuários, matriz, backups, importação) |
| **Diretoria** | tudo do nível de usuário: painel, agenda (e editar os adesivadores), pátio e prateleira, produtividade, relatório, consultar e abrir OS |
| **Comercial** | painel, agenda, pátio e prateleira, consultar OS, abrir OS |
| **Financeiro** | a tela **Meu setor** do Financeiro (recebe as OS e é o **único que conclui**), painel e cadastro de usuários, inclusive redefinir senha — sem criar nem alterar administrador |
| **Operacional** (setores produtivos) | só a tela **Meu setor**: receber, devolver e despachar as OS do próprio setor |

### Tela "Pátio e prateleira"

Prateleira e Pátio são locais de espera, sem operador: quem leva a OS até lá (Acabamento,
Frota) não a tira de lá, e o Financeiro não a puxa. A saída para o Financeiro é **só do
Comercial, da Diretoria e do Administrador** — o servidor recusa qualquer outro perfil.

A tela **Pátio e prateleira** (menu desses três perfis) mostra os dois **lado a lado**
(um embaixo do outro no celular), cada OS com o cliente, o serviço da agenda (ex.:
`TAXI 888 SPIN COMPLETO`), quem abriu e há quanto tempo espera, a mais antiga primeiro. A
**busca** acha a OS pelo número, pelo cliente, pelo serviço ou pelo vendedor que abriu —
sem diferença de acento ou maiúscula, e com várias palavras (`karina spin`) todas precisam
bater. O botão **Enviar ao Financeiro** pede uma
confirmação e manda a OS; lá ela espera ser recebida, como em qualquer setor. A lista se
atualiza sozinha a cada 30 segundos.

### Tela "Minha agenda" (adesivadores)

Quem tem uma coluna na agenda vê a aba **Minha agenda**: só os carros **dele**, dia a dia
(setas para o dia anterior e o próximo), pensada para o celular. Cada carro mostra o horário
**naquele dia** (um serviço longo aparece em cada dia que ocupa, com "começou" e "segue até"),
o status, a OS com o cliente e onde ela está, e o vendedor. Quando a OS do carro chega na
Frota, aparece o botão **Receber OS** (o carro passa a *Executando*); recebida, aparecem
**Pronto — entregar no Pátio** (o carro fica *Concluído*) e **Devolver** para o setor de onde
veio, os dois com confirmação.

**A Frota trabalha só por aqui:** quem é da Frota e tem coluna na agenda não vê a tela Meu
setor (todas as OS do setor) — entra direto na Minha agenda. Os outros setores continuam com
a tela Meu setor. Uma OS na Frota **sem carro na agenda** não aparece para ninguém da Frota:
vincule a OS ao carro na agenda.

A coluna é da pessoa pelo **nome** (a coluna ANDRE é do usuário Andre). Quem adesiva e é de
outro setor vê a agenda, mas quem recebe a OS é a Frota.

### Tela "Meu setor"

É tudo que um setor produtivo vê, e de propósito não tem mais nada:

* **Chegaram para receber** — as OS que outro setor (ou o comercial) mandou. Botão
  **Receber**.
* **No setor** — as que já foram recebidas. Botões **Despachar** e, quando a OS veio de outro
  setor, **Devolver para (setor de onde veio)**.
* **Despachar** abre os destinos possíveis em botões grandes; tocar num deles envia a OS.
  **Devolver** pede uma confirmação.
* **No Financeiro**, no lugar do Despachar vem o **Concluir** (com confirmação): o Financeiro
  é o único setor que encerra a OS dentro do sistema. Chegar ao Financeiro não encerra nada —
  a OS espera ser recebida, como em qualquer setor.
* Não há campo para digitar número de OS nem link para outras telas. A lista se atualiza
  sozinha a cada 30 segundos.

Para onde cada setor pode despachar (a matriz de transição, editável na Administração):

| Setor | Despacha para |
| :--- | :--- |
| Criação | Recorte, Impressão, Acabamento, Preparação, Frota |
| Impressão | Recorte, Acabamento, Preparação, Frota |
| Recorte | Acabamento, Preparação, Frota |
| Acabamento | Prateleira (material aguardando retirada/entrega) |
| Preparação | Frota |
| Frota | Pátio (carro aguardando retirada do cliente) |
| Prateleira / Pátio | Financeiro — **só** o Comercial, a Diretoria ou o Administrador, na tela **Pátio e prateleira** |
| Financeiro | ninguém: recebe e **conclui** (só o perfil Financeiro) |

O **Devolver** manda a OS de volta para o setor de onde ela veio e fica registrado como
retorno na linha do tempo. Ele só aparece quando esse setor não é um destino normal: uma OS
que a Impressão devolveu para a Criação volta para a Impressão pelo **Despachar**, sem um
"devolver" em pingue-pongue.

## Primeiro acesso e senhas

* **Entra-se com o nome de usuário** (ex.: `joao.silva`), não com e-mail. O usuário é
  definido no cadastro: minúsculas, sem espaço nem acento, pode ter ponto, hífen e
  sublinhado. Maiúsculas ao digitar não fazem diferença. O e-mail ficou opcional, só como
  contato — quem ainda digitar o e-mail antigo também entra.

* **Senha provisória.** Quem entra com a senha de demonstração (`123456`) ou com uma senha
  definida pelo administrador só vê a tela **Escolha sua senha** — e o servidor recusa
  qualquer outra chamada até a troca. A senha nova precisa de 8 caracteres, não pode ser
  óbvia (`12345678`, `senha123`...) nem o próprio usuário ou e-mail.
* **Trocar a senha depois:** link *Trocar senha* no topo da tela.
* **Excluir ou desativar.** Na lista de usuários, **Excluir** apaga a conta de vez (pede
  confirmação) — serve para contas antigas ou cadastradas errado. Só é possível para quem
  **não tem nenhum registro** no histórico (OS abertas, recebidas ou movimentadas,
  carros criados na agenda): o histórico guarda quem fez cada coisa e não pode ficar
  apontando para ninguém. Para quem já trabalhou, o sistema recusa e pede para
  **desativar** (Editar → desmarcar *Usuário ativo*): a pessoa não entra mais e o histórico
  continua com o nome dela. Ninguém exclui a própria conta, o Financeiro não exclui
  administrador e o último administrador não pode ser excluído.
* **Banco novo em produção** (`CARGA_DEMO=false`, padrão do `iniciar-producao.ps1`): nascem
  só perfis, setores, a matriz de transição e o usuário `admin`, com uma
  senha aleatória provisória que aparece **uma vez** no log da primeira partida.
* **Banco novo em desenvolvimento** (`CARGA_DEMO=true`): também os usuários abaixo e uma
  massa de OS fictícias. Para zerar tudo, apague a pasta `backend/data/` e suba de novo.

| Usuário | Perfil | Setor |
| :--- | :--- | :--- |
| `vendedor` | Comercial | — |
| `vendedor2` | Comercial | — |
| `diretoria` | Diretoria | — |
| `admin` | Administrador | — |
| `criacao` | Operacional | Criação |
| `impressao` | Operacional | Impressão |
| `recorte` | Operacional | Recorte |
| `preparacao` | Operacional | Preparação |
| `acabamento` | Operacional | Acabamento |
| `frota` | Operacional | Frota |

A **chave que assina os logins** (JWT) não é mais fixa no código: sem `JWT_SECRET`, cada
instalação gera a sua em `backend\data\jwt.secret` na primeira partida. O console do H2
fica **fechado** (`H2_CONSOLE=true` para abrir, só em desenvolvimento).

## Lançamentos fictícios para teste

Para testar com o sistema "cheio", com os usuários reais já cadastrados, dá para gerar
um mês de movimento fictício:

* OS abertas pelos vendedores ao longo dos **últimos 30 dias**, cada uma passando pelos
  setores (com os operadores de cada setor), chegando ao Pátio ou à Prateleira, liberada
  pelo comercial e recebida e concluída pelo Financeiro. As mais recentes param onde
  estariam hoje: aguardando, em processamento, no Pátio...
* A **agenda** dos adesivadores cheia, de 30 dias atrás até duas semanas à frente, sem
  cobrir nenhum carro que já estava lá: táxis completos (2 a 3 dias, ex.: `TAXI 888 SPIN
  COMPLETO`), caminhões (3 dias), vans, picapes, carros de 2 horas, motos e alguns no
  Encaixe. Na Frota, quem recebe e despacha é o próprio adesivador do carro. O score já vem
  lançado nos concluídos (menos em parte da última semana, para lançar no relatório).
* **Acabamento**: placas, adesivos de logo, banners, que saem pela Prateleira.
* OS numeradas a partir de **90001**, para não confundir com as do ERP.

Com a aplicação **parada**, a partir de `backend/`:

```bash
java -jar target/os-tracker-api-1.0.0.jar --ostracker.ficticio.gerar=true
```

Roda uma vez só: se já houver fictício no banco, não gera de novo. Para **apagar** tudo o
que foi gerado — e só isso; OS e carros lançados de verdade ficam — suba uma vez com:

```bash
java -jar target/os-tracker-api-1.0.0.jar --ostracker.ficticio.apagar=true
```

Tudo o que é fictício leva a marca `[ficticio]` (na abertura da OS e na observação do
carro da agenda), e é por ela que a limpeza encontra o que apagar. A limpeza também zera
a pilha do Desfazer da agenda.

## Banco: migrações, backup e restauração

### Migrações (Flyway)

O schema é versionado em `backend/src/main/resources/db/migration` e aplicado na partida:

| Versão | O que faz |
| :--- | :--- |
| V1 | schema de partida (num banco anterior ao Flyway vira linha de base e não roda) |
| V2 | colunas de enum passam a texto (o tipo `ENUM` do H2 não existe no PostgreSQL) |
| V3 | encurta as horas dos serviços **importados** que invadiam o seguinte — ver [a agenda](#o-empurrão-só-alcança-quem-a-edição-atingiu) |
| V4 | pilha do desfazer no banco |
| V5 | marca de senha provisória |
| V6 | perfil Financeiro |
| V7 | login por nome de usuário (as contas existentes ganham a parte do e-mail antes do @) |
| V8 | cadastro de feriados, já com os nacionais de 2026 e 2027 |
| V9 | sexta até as 17:00: carros que ocupavam 17h–18h de sexta perdem essa hora e terminam onde terminavam |

Mudança de tabela é **sempre** uma migração nova; o Hibernate não cria nem altera nada
(`ddl-auto: none`).

### Backup

* **Automático** todo dia útil às 12:30 e **antes de qualquer migração** num banco que já
  tem dados — uma atualização que dê errado tem volta.
* Guarda os **30** mais recentes em `backend\backups` (dump SQL compactado, que restaura
  até numa versão futura do H2).
* Tela **Administração → Backups**: lista e *Fazer backup agora*.

### Restaurar

Com a aplicação **parada**:

```bash
powershell -ExecutionPolicy Bypass -File .\restaurar-backup.ps1
```

Sem parâmetros, lista os backups. Com `-Arquivo <nome>`, restaura aquele. O banco atual
não é apagado: fica guardado ao lado como `ostracker.mv.db.antes-de-restaurar-<data>`.

### PostgreSQL

O `PostgresTest` roda a aplicação inteira sobre PostgreSQL 14, então a troca é só de
conexão. No PowerShell, antes de subir:

```bash
$env:DB_URL = 'jdbc:postgresql://localhost:5432/ostracker'
```

```bash
$env:DB_USER = 'postgres'
```

```bash
$env:DB_PASSWORD = '<senha>'
```

```bash
$env:DB_DRIVER = 'org.postgresql.Driver'
```

No PostgreSQL o backup é do servidor de banco (`pg_dump`); a tela de backups avisa isso.

### Variáveis de ambiente

| Variável | Padrão | Para quê |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | porta |
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_DRIVER` | H2 em `./data/ostracker` | banco |
| `JWT_SECRET` | gerada em `data/jwt.secret` | chave dos logins (mín. 32 caracteres) |
| `JWT_EXP_HORAS` | `12` | validade do login |
| `CARGA_DEMO` | `true` (`false` no `iniciar-producao`) | dados fictícios num banco novo |
| `BACKUP_PASTA`, `BACKUP_MANTER`, `BACKUP_CRON` | `./backups`, `30`, `0 30 12 * * MON-FRI` | backup do H2 |
| `H2_CONSOLE` | `false` | console web do H2 |
| `CORS_ORIGENS` | `localhost:5173` | só para o front de desenvolvimento |

## Versionamento

O projeto é um repositório Git (`main`), com `target/`, `node_modules/`, `dist/`, o banco,
os backups e os logs fora do controle de versão.

Cada mudança que vale história vira um **commit nomeado**, com mensagem explicando o quê e o
porquê. Versões publicadas levam uma tag (`v1.0.0`, ...).

O script opcional [`scripts/commit-automatico.ps1`](scripts/commit-automatico.ps1) é uma rede
de segurança para sessões de desenvolvimento assistidas: se sobrou alteração sem commit ao
fim de uma sessão, ele a guarda num commit `checkpoint: ...`. Ele não substitui o commit
nomeado — muitos `checkpoint:` no histórico são sinal de que algo escapou do fluxo normal.

## API REST

| Método | Rota | Perfil | O que faz |
| :--- | :--- | :--- | :--- |
| POST | `/api/auth/login` | público | Autentica com `usuario` e `senha` e devolve o JWT |
| GET | `/api/auth/me` | autenticado | Dados do usuário logado (inclui `trocarSenha`) |
| POST | `/api/auth/trocar-senha` | autenticado | Troca a própria senha — única rota liberada com senha provisória |
| GET | `/api/setores` · `/api/setores/iniciais` | autenticado | Setores; setores válidos para abertura |
| POST | `/api/ordens` | Comercial/Diretoria/Admin | Abre a OS com um ou mais fluxos (RF01) |
| POST | `/api/ordens/{id}/fluxos` | Comercial/Diretoria/Admin | Acrescenta fluxo paralelo (RN03) |
| GET | `/api/ordens/{id}` | Comercial/Diretoria/Admin | OS com todos os seus fluxos |
| POST | `/api/ordens/{id}/cancelar` | Comercial/Diretoria/Admin | Cancela a OS inteira (RF06) |
| GET | `/api/ordens/abertas` | Comercial/Diretoria/Admin | OS em andamento, para o seletor da agenda |
| GET | `/api/fluxos?termo=&status=&setor=` | Comercial/Diretoria/Admin | Consulta de fluxos (RF05), filtrando pelo setor onde a OS está |
| GET | `/api/fluxos/{id}` | Comercial/Diretoria/Admin | Timeline + ações disponíveis |
| GET | `/api/movimentacao` | Operacional/Financeiro | Tela do setor: o que chegou para receber e o que está no setor, com destinos |
| POST | `/api/fluxos/{id}/receber` | quem trabalha no setor (o Financeiro no setor Financeiro) | Recebimento (RN01) |
| POST | `/api/fluxos/{id}/devolver` | quem trabalha no setor | Devolve ao setor de onde a OS veio (retorno) |
| POST | `/api/fluxos/{id}/concluir` | Financeiro | Conclui o fluxo, com a OS já recebida no Financeiro — o único jeito de encerrar |
| POST | `/api/fluxos/{id}/despachar` | Operacional do setor; Prateleira/Pátio: Comercial/Diretoria/Admin | Despacho/entrega pela matriz (RF03, RF04) |
| POST | `/api/fluxos/{id}/cancelar` | Comercial/Diretoria/Admin | Cancela um fluxo |
| GET | `/api/painel?inicio=&fim=` | Comercial/Diretoria/Admin/Financeiro | Contagens de agora e do período (OS abertas, concluídas, canceladas, saídas por setor) |
| GET/POST/PUT | `/api/admin/usuarios` | Admin/Financeiro | Gestão de usuários (RF07); a senha definida aqui é provisória; o Financeiro não mexe em administrador |
| DELETE | `/api/admin/usuarios/{id}` | Admin/Financeiro | Exclui de vez quem não tem histórico; quem tem deve ser desativado |
| GET/PATCH | `/api/admin/setores` | Admin | Ativa/inativa setores |
| GET/POST/DELETE | `/api/admin/transicoes` | Admin | Matriz de transição parametrizável |
| GET/POST | `/api/admin/backups` | Admin | Lista os backups / faz um na hora |
| GET/POST/DELETE | `/api/admin/feriados[/{id}]` | Admin | Feriados: dias que não contam no tempo das OS |
| GET | `/api/agenda/semana?data=` | Comercial/Diretoria/Admin | Bloco semanal da agenda, com as `continuacoes` da semana anterior |
| GET | `/api/fluxos/em-espera` | Comercial/Diretoria/Admin | OS na Prateleira e no Pátio, com os serviços da agenda |
| GET | `/api/vendedores` | qualquer usuário | Vendedores da agenda: código (1ª letra do nome) e nome, montados dos usuários do Comercial e da Diretoria |
| GET | `/api/agenda/colunas` | Comercial/Diretoria/Admin | Adesivadores + colunas Encaixe/Noturno (inclusive os removidos, com `ativo=false`) |
| POST | `/api/agenda/colunas` | Diretoria/Admin | Adiciona um adesivador, antes de Encaixe e Noturno (`{nome}`) |
| PUT | `/api/agenda/colunas/{id}` | Diretoria/Admin | Renomeia (`{nome}`) |
| PUT | `/api/agenda/colunas/ordem` | Diretoria/Admin | Nova ordem: lista com os ids de todas as colunas da agenda |
| DELETE | `/api/agenda/colunas/{id}` | Diretoria/Admin | Remove (recusa se houver carro de hoje em diante) |
| POST | `/api/agenda/colunas/{id}/restaurar` | Diretoria/Admin | Devolve um removido à agenda, antes de Encaixe e Noturno |
| GET | `/api/agenda/por-os/{osId}` | Comercial/Diretoria/Admin | Carros agendados para uma OS |
| GET | `/api/agenda/busca?termo=&somenteSemOs=` | Comercial/Diretoria/Admin | Procura carro na agenda |
| POST/PUT/DELETE | `/api/agenda[/{id}]` | Comercial/Diretoria/Admin | Cria, edita e remove agendamento |
| PATCH | `/api/agenda/{id}/status` | Comercial/Diretoria/Admin | Marca o andamento do carro |
| PATCH | `/api/agenda/{id}/mover` | Comercial/Diretoria/Admin | Move ou troca de lugar (arrastar) |
| PATCH | `/api/agenda/{id}/inicio` | Comercial/Diretoria/Admin | Borda de cima: novo início (`data`, `slotInicio`), o fim fica |
| PATCH | `/api/agenda/{id}/horas` | Comercial/Diretoria/Admin | Muda as horas estimadas do serviço |
| PATCH | `/api/agenda/{id}/os` | Comercial/Diretoria/Admin | Liga/desliga o carro de uma OS |
| POST | `/api/agenda/desfazer` | Comercial/Diretoria/Admin | Desfaz a última alteração da pessoa |
| GET | `/api/produtividade?inicio=&fim=` | Diretoria/Admin | Indicadores por setor e por adesivador |
| GET | `/api/produtividade/semanal?data=` | Diretoria/Admin | Relatório da semana, serviço por serviço |
| PATCH | `/api/produtividade/semanal/servicos/{id}/score` | Diretoria/Admin | Lança o score do serviço e devolve a semana recalculada |
| POST | `/api/admin/agenda/importar` | Admin | Importa uma aba do cronograma — só em período vazio |
| POST | `/api/admin/agenda/completar` | Admin | Reparo: grava só os dias ausentes e lista divergências de score |

## Painel

O **Painel** mostra duas coisas: **agora** (OS ativas, aguardando recebimento, em
processamento, e em cada setor quantas estão na fila e em curso) e o **período escolhido**
— **Dia, Semana, Mês ou Ano**, com setas para andar no tempo e *Hoje* para voltar: OS
abertas, concluídas e canceladas no período e quantas **saíram de cada setor**. A semana é
cortada no mês, como na agenda. Abre no dia de hoje.

## Produtividade (indicadores dentro do sistema)

A tela **Produtividade** (Diretoria e Admin) mede **cada setor e, dentro dele, cada
pessoa**, por **mês** (padrão: o mês inteiro) ou por uma **semana do mês** — a navegação é
a mesma da agenda. Tudo é calculado **na leitura**, a partir da trilha de eventos e da agenda — nada
é armazenado em paralelo (RNF01), e o banco continua aberto para BI.

### Tempo em horário comercial

Todo tempo de OS conta **só com a empresa aberta**: segunda a sexta, das **07:30 às 12:00 e
das 13:30 às 18:00 — na sexta até as 17:00** (44 horas por semana, como a agenda), fora os
**feriados** cadastrados. Uma OS que chega sexta às 16:00 e é recebida segunda às 08:00
esperou **1h30** (1h na sexta e 30 min na segunda), e não 64 horas — senão o setor que
recebe muita coisa no fim da tarde pareceria lento sem ser.

Vale para a espera, o processamento e o tempo no setor desta tela, e para o "parado há"
de Meu setor, Pátio e prateleira, Consultar OS e do detalhe da OS. Nas telas, "dia" é
**dia útil de 9 horas** (`2 dias úteis 3 h`). Nada foi gravado de outro jeito: os eventos
guardam o horário exato, e a conta é feita na leitura — o histórico todo já aparece assim.

**Feriados** ficam em *Administração → Feriados*. Os nacionais de 2026 e 2027 (com Carnaval
e Corpus Christi) já vêm cadastrados; inclua os da cidade e do estado e remova um ponto
facultativo em que a empresa trabalhe. A mudança vale na hora, inclusive para o passado.

| Área | O que mede | Por pessoa |
| :--- | :--- | :--- |
| **Comercial** | OS abertas | quem abriu a OS |
| **Setores de produção** (Criação, Impressão, Recorte, Preparação, Acabamento, Frota) | OS processadas e o **tempo médio no setor** | quem **recebeu** a OS — o responsável pela etapa (RN01) |
| **Financeiro** | OS concluídas e o tempo até concluir | quem recebeu e concluiu |
| **Prateleira / Pátio** | quantas saíram e quanto tempo esperaram retirada | — são lugares, sem responsável |
| **Adesivadores** | serviços feitos e a **pontuação** | cada adesivador da agenda |

* **Processada** = a OS **saiu** do setor no período: despachada, devolvida, enviada ao
  Financeiro ou, no Financeiro, concluída. Uma OS que chegou e ainda está lá não conta.
* **Tempo no setor** vai da **chegada** da OS até a **saída**. Na tabela ele aparece dividido
  em *esperando receber* (chegou → alguém recebeu) e *em trabalho* (recebeu → saiu).
* **Devolvidas** são as OS que o setor mandou de volta ao anterior: retrabalho.
* Só entram as saídas que caem no período escolhido (7, 30 ou 90 dias); senão uma OS antiga
  parada contaminaria a média.
* A conta é por **fluxo**: uma OS com dois fluxos (Lona e Adesivo) que passam pela Impressão
  conta duas vezes lá, porque foram dois trabalhos.

**Adesivadores** — **serviço feito** é o que está concluído na agenda; a **pontuação** soma o
score desses serviços, lançado no relatório semanal. As colunas `Encaixe` e `Noturno` ficam
de fora: são colunas da grade, não pessoas.

> **Por que não por hora.** Todos os adesivadores cumprem a mesma carga horária, então
> hora mede o quanto foi *agendado* para a pessoa, não o quanto ela entregou. O que
> discrimina é serviço feito e a **pontuação**: na semana de 01/09 o Caio fez 13 serviços
> com 35,5 pontos e o Andre fez 7 com 40,5 — contar serviço sozinho inverteria a leitura.

Todo valor aparece escrito na ponta da barra, e **Ver tabelas** mostra os mesmos números em
tabela (com a divisão espera/trabalho) — nenhum número depende de passar o mouse.

## Relatório semanal

A tela **Relatório** lista, semana por semana (ou o **mês inteiro**, para os pontos do mês)
e adesivador por adesivador, **todo serviço
que passou pela agenda**: dia, horário, carro, vendedor, OS (com link), situação do
material, status e score. Bloqueios de falta/férias aparecem recessivos na lista.

Serviço que **passa do fim do dia** mostra, além do horário, o dia em que termina e em
quantos dias se estende: `07:30 → QUA 06/03 12:00  3 dias`. O fim de semana é pulado,
como na agenda.

No topo, **Pontos da semana** soma os pontos de cada adesivador, do maior para o menor, com
o total da equipe: *Pontos* conta só os serviços concluídos; *Pontos lançados* conta todos
os que já têm score, concluídos ou não.

O cabeçalho de cada pessoa resume a semana (`7 de 7 concluídos · pontos 40,5`), e o botão
**Imprimir** gera a versão de papel — sem menu, sem botões, com os chips em preto e branco
e sem quebrar a tabela de uma pessoa no meio.

### É aqui que o score é lançado

O score se digita **na linha do serviço, neste relatório** — não na agenda. Na planilha ele
era escrito na própria grade porque não havia outro lugar; a agenda é o plano do dia, e o
score é o peso do que foi entregue, que só se conhece olhando a lista pronta.

Cada linha tem um campo: aceita vírgula (`3,5`), salva ao sair do campo ou no **Enter**,
**Esc** desiste do que foi digitado e apagar o conteúdo remove o score. O total da pessoa
no cabeçalho recalcula a cada lançamento — ele soma **só os concluídos**, então lançar
score num serviço que ainda não terminou não move o total, de propósito.

Quem lança: Diretoria e Admin (o relatório é deles). Bloqueio de horário não recebe score, nem pela
tela (mostra `—`) nem pela API (recusa com 422). Editar o carro na agenda **não toca** no
score; virar bloqueio o apaga.

## Agenda dos adesivadores

### Por mês, com a semana cortada na virada

Agenda, Produtividade e Relatório navegam **por mês**, como as abas da planilha: setas para
o mês anterior e o seguinte, e as semanas do mês em botões (`Sem 1 01–02/10`, `Sem 2 05–09/10`…).
A semana que cai em dois meses é **cortada na virada**: 28/09 a 02/10 aparece como 28–30/09
em setembro e 01–02/10 em outubro. Um serviço que começou no mês anterior aparece no começo
do mês seguinte como continuação. *← Semana* e *Semana →* atravessam a virada.

### Sexta até as 17:00 — 44 horas por semana

Na sexta a empresa fecha às 17:00: a faixa das **17:00 às 18:00 da sexta** aparece como
*fechado* (igual ao almoço) — não recebe carro, e um serviço longo pula ela e continua na
segunda. A semana de cada adesivador tem **44 horas** (9h de segunda a quinta, 8h na sexta).
Os carros que já ocupavam esse horário perderam essa hora e **terminam onde terminavam**
(migração V9) — ninguém foi empurrado.

A **carga** de cada adesivador no topo da coluna conta só as horas que caem de fato nos
dias mostrados: um caminhão que começa na quinta leva para a semana só o que ocupa de quinta
e sexta, e o que veio da semana anterior também entra. Assim ela nunca passa da capacidade.

A agenda reproduz o cronograma que era mantido no Google Sheets: uma coluna por adesivador
(mais `Encaixe` e `Noturno`) e, em cada dia, as **faixas de horário reais**:

| Faixa | Horário | Horas |
| :--- | :--- | :--- |
| 1 | 07:30 às 09:00 | 1h30 |
| 2 | 09:00 às 10:00 | 1h |
| 3 | 10:00 às 11:00 | 1h |
| 4 | 11:00 às 12:00 | 1h |
| 5 | 12:00 às 13:30 | **almoço** |
| 6 | 13:30 às 15:00 | 1h30 |
| 7 | 15:00 às 16:00 | 1h |
| 8 | 16:00 às 17:00 | 1h |
| 9 | 17:00 às 18:00 | 1h |

São **9 horas úteis por dia** (8 na sexta, que termina às 17:00). O almoço aparece na grade para dar noção do dia, mas
**nunca é preenchido**: não recebe início de serviço, não conta hora e não é destino de
arraste. Um serviço que atravessa o meio-dia continua **um bloco só**, com uma tarja
listrada por dentro marcando a pausa.

O cabeçalho de cada adesivador mostra a carga da semana (`32h / 44h`) com uma barra, que
fica vermelha quando a semana lota.

### Horário escolhido é horário respeitado

Um serviço fica **exatamente onde foi colocado**. Marcar um cliente às 13:30 numa segunda
vazia não o puxa para as 07:30 só porque é o primeiro horário livre — buracos na agenda
são intencionais e permitidos. Encolher ou excluir um serviço abre um buraco; ninguém sobe
para ocupá-lo sozinho.

### O empurrão só alcança quem a edição atingiu

Quando um serviço cresce, muda de lugar ou é criado por cima de outro, **quem ele encosta
desce** — e quem esse encostar também, em cascata, continuando no próximo dia útil se não
couber. Na mesma faixa, **o editado fica e o outro desce**: marcar férias às 07:30 de um
dia ocupado empurra os serviços daquele dia, e não o bloqueio.

O que a edição **não** atinge não se mexe. Salvar um carro sem mudar horário, horas, coluna
ou tipo não reempilha nada.

> A importação antiga convertia linhas da planilha em horas arredondando para cima, e
> 1.056 serviços importados invadiam o seguinte. Cada salvamento "corrigia" isso empurrando
> a agenda por semanas — um salvamento sem mudança chegou a mover 21 carros, inclusive da
> semana corrente. A migração V3 encurtou as horas desses serviços importados até onde o
> seguinte começa, **sem mudar data, faixa de início nem score**. Sobraram 13 dias que a
> planilha lotou com dois carros na mesma faixa; eles ficam como estão até alguém mexer.

Um serviço que **começou numa semana anterior** e ainda ocupa a segunda-feira aparece no
alto da semana, cortado, com a marca `↑ desde 18/09` — antes essa manhã parecia livre.

### Indisponibilidade (falta, férias, feriado)

No editor, a faixa pode ser **Carro / serviço** ou **Indisponível**. O bloco de
indisponibilidade tem motivo (`FÉRIAS`, `FALTA`, `ATESTADO`, `FERIADO`, `TREINAMENTO` ou
texto livre), ocupa a agenda do adesivador e empurra os serviços como qualquer outro — mas
não tem OS, vendedor nem andamento.

As horas bloqueadas **saem da capacidade** da semana: um adesivador com dois dias de férias
aparece como `6h / 26h` com a marca `−18h indisp.`, em vez de `6h / 44h`.

### O card

Cada card tem **duas linhas de altura fixa**, para a grade ficar uniforme e legível de
relance:

```
FASTBACK PPF FULL        ← nome do projeto, em destaque
Karina · ● OS 5050        ← vendedor e OS, discretos
```

A bolinha antes da OS é o **andamento do material**: verde = pronto, âmbar = em produção.
Sem OS vinculada, a linha diz `sem OS`. O horário não se repete no card porque já está na
coluna da esquerda. Passando o mouse, o tooltip traz tudo: horário, duração, vendedor e a
situação de cada fluxo.

O ganho em relação à planilha é o **vínculo com a OS**: ao ligar um carro a um número de
OS do ERP, o card passa a mostrar se o material está pronto, e a tela da OS passa a listar
para quando aquele carro está agendado.

Status seguem a legenda original: `PROGRAMADO`, `EM_PATIO`, `EXECUTANDO`, `CONCLUIDO`,
`NAO_VEIO`, `EXTERNO`.

O **score não se lança na agenda** — ele é atribuído no [relatório semanal](#é-aqui-que-o-score-é-lançado).
A grade mostra a carga em horas por adesivador, que é o que importa para planejar o dia.

### Editar os adesivadores (Diretoria e Administrador)

O botão **✎ Editar adesivadores**, no topo da agenda, abre o modo edição das colunas:

* **Adicionar** — entra depois do último adesivador (antes de Encaixe e Noturno), com o nome
  em maiúsculas, como na planilha.
* **Renomear** — os carros continuam com ele; não pode repetir o nome de outra coluna.
* **↑ / ↓** — muda a ordem das colunas (da esquerda para a direita), gravada na hora.
* **Remover** — só sai quem **não tem carro de hoje em diante**; se tiver, o sistema diz
  quantos e pede para arrastá-los para outro adesivador antes. Quem já trabalhou sai da
  agenda mas **continua nas semanas passadas**, no relatório semanal e na produtividade,
  e aparece em *Removidos da agenda* com o botão **Restaurar**. Quem nunca teve carro é
  apagado de vez.
* Adicionar de novo um nome removido (ou a importação da planilha trazer esse nome)
  **restaura** a coluna antiga, com o histórico.

O Comercial vê a agenda e mexe nos carros, mas não edita as colunas.

O **nome do serviço aparece sempre inteiro** no card, sem precisar passar o mouse: ele quebra
em quantas linhas precisar, e a faixa da grade cresce quando um card curto não comporta o nome.

### Mexer na agenda — com mouse ou com o dedo

| Para | No computador | No tablet |
| :--- | :--- | :--- |
| Selecionar um carro ou uma faixa livre | um clique | um toque |
| Abrir o carro / criar um na faixa livre | dois cliques | dois toques |
| Mudar de horário ou de adesivador | arrastar e soltar | **segurar o dedo** até o carro "pegar" (vibra de leve), arrastar e soltar |
| Mudar a duração | puxar a barrinha da borda de baixo | idem, com o dedo (a barrinha fica maior) |
| Mudar o início (o fim fica) | puxar a barrinha da borda de cima | idem, com o dedo |

Soltando **em cima de outro carro, os dois trocam de lugar** — nada é apagado, e o vínculo
com a OS acompanha o carro. Cada carro **leva a sua quantidade de horários**: um card de 3 horários
continua com 3 onde cair (as horas se ajustam, porque as faixas das 07:30 e das 13:30 têm
1h30). Na **mesma coluna**, os dois trocam de ordem e ficam encostados como estavam — o que
vinha depois passa a começar onde o primeiro começava, o outro vem logo em seguida (com o
mesmo espaço vago que havia entre eles; o almoço não conta como espaço) e o par termina onde
terminava, sem empurrar ninguém. Entre **colunas diferentes**, cada um vai para o início do
outro. Mover para uma faixa vazia também mantém a quantidade de horários. No tablet, mexer o dedo antes de o carro "pegar" continua
rolando a tela normalmente.

A barrinha estica ou encolhe direto na grade: **arraste até a linha onde o serviço deve
terminar**, inclusive nos dias seguintes. A duração sai das faixas percorridas, então
respeita faixas de 1h e 1h30 e pula os almoços do caminho. Enquanto você puxa, a grade já
mostra quem vai descer. Num carro que veio da semana anterior a barrinha não aparece: ali
a duração se ajusta pelo editor.

A **barrinha de cima** (um pegador pequeno no centro da borda — o resto do card, inclusive
o nome, continua sendo onde se segura para arrastar) faz o contrário: **arraste até a linha onde o serviço deve
começar** — o fim continua no mesmo lugar e as horas se recalculam (pulando o almoço). Dá
para subir até o dia anterior, mas só por horário livre: ela para no carro de cima, que não
é empurrado nem coberto. Descendo, o card encolhe. Um Ctrl+Z desfaz.

### Duração em horas ou em dias

O serviço é medido em **horas estimadas**. O editor oferece duas réguas: **até um dia**
(30min a 8h) e **dias inteiros** (1 a 15 dias, contando 9h úteis cada). Escolhida a
duração, o sistema preenche sozinho os horários que serão ocupados — avisando quando
atravessa o almoço ou vira o dia. Um serviço que vira o dia mostra `+1d`.

### A agenda acompanha a Frota sozinha

Os carros da agenda **ligados a uma OS** mudam de status quando a Frota mexe nela:

| Na Frota | Na agenda |
| :--- | :--- |
| **recebe** a OS | Programado / Em pátio → **Executando** |
| **despacha para o Pátio** | → **Concluído** |
| **devolve** a OS a quem mandou | Executando → **Programado** |

Carro marcado na mão como *Não veio*, *Externo* ou já *Concluído* fica como está. Se a OS tem
mais de um carro na agenda, todos seguem juntos. Essas mudanças não entram no Ctrl+Z da agenda.

### Copiar, recortar, colar, excluir e desfazer

* **Ctrl+C** com um carro selecionado copia o agendamento (inclusive o vínculo com a OS).
* **Ctrl+X** com um carro selecionado recorta: ele fica esmaecido até o **Ctrl+V**, que o
  **move** para a faixa escolhida — é o mesmo carro, com OS, status e score, e um Ctrl+Z
  devolve. **Esc** ou *Cancelar* desiste.
* **Ctrl+V** com uma faixa livre selecionada cola ali — ou o botão *Colar aqui* da barra
  que aparece enquanto há algo copiado.
* **Delete** com um carro selecionado (um clique nele) exclui na hora, sem pergunta — o
  **Ctrl+Z** traz de volta. No Mac, a tecla *delete* também serve. Com a janela do carro
  aberta, a tecla não age na grade.
* **Ctrl+Z** desfaz a última alteração de agenda — ou o botão `↶ Desfazer`, cujo tooltip
  diz o que será revertido. **Esc** limpa a seleção.

O desfazer guarda um retrato da agenda dos adesivadores afetados antes de cada alteração,
então ele reverte também os serviços que foram **empurrados** pela mudança. A pilha é por
pessoa, fica **no banco** (sobrevive a um reinício) e guarda as **últimas 25 alterações das
últimas 24 horas** — desfazer algo de dias atrás devolveria a janela inteira ao estado
antigo, apagando o que outras pessoas fizeram depois. Auditoria continua sendo a trilha de
eventos imutáveis da OS.

Quem remaneja é Comercial, Diretoria ou Admin.

### Vínculo com a OS

A OS não é digitada: é escolhida numa lista das **OS em andamento** (exclui as encerradas
no Financeiro e as canceladas), mostrando número, cliente e quantidade de fluxos ativos.
Uma OS pode atender **vários carros** da agenda; cada carro aponta para **uma única** OS.

Quem faz o quê:

* **Comercial / Diretoria / Admin** — montam a agenda, editam, vinculam a OS e marcam o
  andamento do carro.
* **Demais perfis** — não acessam a agenda (os setores só recebem e despacham).

### Importar do Google Sheets

A agenda vive no OS Tracker; a planilha é histórico. Por isso a importação **só grava em
período vazio**: se o sistema já tem agenda em algum dia que a aba cobre, ela é recusada e
nada é gravado — não há como duplicar carros nem desfazer o que foi remanejado aqui.

```bash
powershell -ExecutionPolicy Bypass -File .\importar-agenda.ps1 -Meses Novembro,Dezembro
```

**Qual planilha:** o endereço da planilha da empresa **não fica no código**. O script usa, nesta
ordem, o parâmetro `-PlanilhaId <id>`, a variável de ambiente `PLANILHA_AGENDA_ID` ou o
arquivo `planilha-agenda.id` na pasta do projeto (um arquivo de uma linha, ignorado pelo git).
O ID é o trecho do endereço entre `/d/` e `/edit`.

O script pede a senha do usuário `admin` (outro: `-Usuario <nome>`). Parâmetros úteis: `-Ano 2027` e
`-APartirDe 2026-09-21` (só aquele dia em diante; o resto da aba é ignorado).

As abas usadas nos testes (`backend/src/test/resources/planilha`) são cópias **anonimizadas** de
agosto e setembro de 2026: nomes, clientes e placas trocados, números e estrutura preservados —
os testes continuam conferindo cada semana contra o `TOTAL SCORE` da própria planilha.

Como a planilha vira agenda:

* cada linha do dia vira as faixas de horário do pedaço do dia que ela representava — com
  5 linhas, cada uma vale 1,8h; com 6, 1,5h. O dia importado sempre cabe nas 9h e uma
  linha vazia vira horário livre;
* linhas seguidas com o mesmo serviço viram um agendamento só, **somando o score** das
  linhas, como a própria planilha faz no `TOTAL SCORE`;
* a semana de um dia só no fim da aba (`DIA 31/08`) também entra.

**Reparo da base importada antes destas correções:**

```bash
powershell -ExecutionPolicy Bypass -File .\importar-agenda.ps1 -CompletarDiasAusentes
```

Grava **só os dias em que o sistema não tem nenhum agendamento em nenhuma coluna** — a
marca do dia que a versão antiga pulava — e **lista** as diferenças de score dos demais
dias, sem alterar nada.

## Regras implementadas no servidor

* **RN01** — o primeiro a clicar em *Receber* fica gravado como responsável da etapa; um
  segundo recebimento é recusado (422).
* **RN02** — cancelar muda o status e grava um evento; nada do histórico é apagado.
* **RN03** — cada fluxo da OS tem setor, status e responsáveis próprios.
* **RN04** — o destino vem sempre da matriz de transição; texto livre não é aceito.
* **RN05** — Acabamento só despacha para Prateleira, Frota só para Pátio, e a saída
  desses locais para o Financeiro é exclusiva de Comercial/Diretoria/Admin.
* **Conclusão** — só o Financeiro conclui o fluxo, e só depois de receber a OS (evento
  `CONCLUSAO`). Fora o cancelamento, é o único jeito de uma OS terminar no sistema.
* **Retorno** — além da matriz, todo setor pode devolver o fluxo apenas ao setor de
  origem imediata (botão **Devolver**), registrado como evento `RETORNO`.
* **RNF01** — a tabela `eventos_movimentacao` é somente-inserção na aplicação.
* **RNF05** — permissões validadas no servidor; senha provisória bloqueada no filtro de
  autenticação, não só na tela.
