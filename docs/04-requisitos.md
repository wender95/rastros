# 4. Requisitos

Todos os requisitos abaixo estão implementados. Onde há um teste automatizado que garante
o requisito, ele está indicado.

## 4.1 Requisitos funcionais

### Rastreio de OS

| # | Requisito | Garantia |
| :-- | :--- | :--- |
| RF01 | **Abrir OS** com o número do ERP, com um ou mais fluxos paralelos, cada um começando num setor de entrada válido (Comercial, Diretoria, Admin). | `AcessosTest`, `MovimentacaoTest` |
| RF02 | **Meu setor**: o operador vê só as OS que chegaram para receber e as que já estão no setor, sem digitar número de OS. Atualiza sozinha a cada 30 s. | `MovimentacaoTest` |
| RF03 | **Receber** grava quem e quando; só depois de receber é possível despachar ou devolver. | `MovimentacaoTest` |
| RF04 | **Despachar** só para os destinos da matriz de transição; **devolver** para o setor de onde a OS veio, quando esse setor não é um destino normal. | `MovimentacaoTest` |
| RF05 | **Prateleira e Pátio**: saída para o Financeiro só pelo Comercial, Diretoria ou Admin, pela tela *Pátio e prateleira* (lado a lado, com busca por número, cliente, serviço ou vendedor). | `MovimentacaoTest`, `AcessosTest` |
| RF06 | **Financeiro** recebe a OS e é o único que a **conclui**. | `MovimentacaoTest` |
| RF07 | **Consultar OS** com filtro por setor, status e texto; linha do tempo de cada fluxo. | `MovimentacaoTest` |
| RF08 | **Cancelar** OS ou fluxo com motivo obrigatório, preservando o histórico. | — |

### Agenda dos adesivadores

| # | Requisito | Garantia |
| :-- | :--- | :--- |
| RF09 | Grade semanal com **faixas de horário reais** (07:30–18:00, almoço e sexta 17h–18h fechados), uma coluna por adesivador, navegada **por mês** com a semana cortada na virada. | `AgendaServiceTest` |
| RF10 | **Arrastar** (mouse e dedo), **redimensionar** pelas duas bordas, **trocar de lugar**, copiar/**recortar**/colar, excluir com Delete e **desfazer** (25 passos, 24 h, persistido). | `AgendaServiceTest`, `ReempilharTest`, `HistoricoAgendaTest` |
| RF11 | **Carga semanal** por adesivador contando só as horas que caem na semana, contra a capacidade (44 h). | `AgendaServiceTest` |
| RF12 | **Vínculo carro ↔ OS**, com o andamento do material; a agenda muda o status do carro quando a Frota recebe, entrega ou devolve a OS. | `MovimentacaoTest`, `MinhaAgendaTest` |
| RF13 | **Minha agenda** do adesivador, no celular: só os carros dele, dia a dia, com Receber, Entregar no Pátio e Devolver. | `MinhaAgendaTest` |
| RF14 | **Editar adesivadores** (Diretoria/Admin): adicionar, renomear, reordenar, remover e restaurar colunas, sem perder histórico. | `ColunasAgendaTest` |
| RF15 | **Importar** o cronograma do Google Sheets (abas mensais), só em período vazio. | `ImportadorAgendaTest`, `PlanilhaGabaritoTest` |

### Indicadores

| # | Requisito | Garantia |
| :-- | :--- | :--- |
| RF16 | **Painel** com o que está em andamento agora e o que aconteceu no dia, semana, mês ou ano. | `PainelTest` |
| RF17 | **Produtividade** por setor e por pessoa: OS abertas por vendedor; processadas e tempo no setor; serviços e pontos dos adesivadores. | `ProdutividadeTest` |
| RF18 | **Relatório** semanal ou mensal por adesivador, com o **score lançado na linha do serviço** e a soma de pontos. | `RelatorioSemanalTest` |

### Administração

| # | Requisito | Garantia |
| :-- | :--- | :--- |
| RF19 | Usuários: criar, editar, desativar, excluir (só sem histórico), redefinir senha. | `AcessosTest`, `ExclusaoUsuarioTest` |
| RF20 | Matriz de transição parametrizável; cadastro de **feriados**; backups sob demanda. | `FeriadoTest`, `BackupServiceTest` |

## 4.2 Requisitos não funcionais

| # | Requisito | Como é atendido |
| :-- | :--- | :--- |
| RNF01 | **Auditoria**: eventos imutáveis; indicadores derivados na leitura, nunca guardados em paralelo. | Tabela `eventos_movimentacao` só recebe inserções |
| RNF02 | **Desempenho**: telas do dia a dia com número fixo de consultas (sem N+1). | `ConsultasTest` |
| RNF03 | **Uso no celular**: telas responsivas; instalável na tela inicial (manifesto e ícones). | Verificação no navegador em 375 px |
| RNF04 | **Segurança**: JWT, BCrypt, senha provisória obrigatória, permissões validadas no servidor. | `AutenticacaoTest`, `AcessosTest` |
| RNF05 | **Banco portável**: H2 em arquivo para uma máquina só; PostgreSQL por configuração. | `PostgresTest` (PostgreSQL 14 embutido) |
| RNF06 | **Operação segura**: migrações versionadas, backup diário e antes de cada migração, restauração testada. | `BackupServiceTest` |
| RNF07 | **Simplicidade de implantação**: um único arquivo `.jar` com API e tela. | `gerar-pacote.ps1` |
