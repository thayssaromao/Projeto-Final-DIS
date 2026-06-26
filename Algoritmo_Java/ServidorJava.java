package Algoritmo_Java;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import com.sun.management.OperatingSystemMXBean;

public class ServidorJava {

    private static final String HOST = "127.0.0.1";
    private static final int PORTA = 8000;

    private static final int TAM_BUFFER = 4096;
    private static final int MAX_TRABALHADORES = 8;
    private static final double LIMITE_CPU = 96.0;

    private static final String PASTA_RESULTADOS = "resultados-relatorio";

    private static final String RESET = "\u001B[0m";
    private static final String COR_TRABALHADOR = "\u001B[96m";
    private static final String COR_TRABALHADOR_ERRO = "\u001B[91m";
    private static final String COR_PROCESSADOR = "\u001B[92m";
    private static final String NEGRITO = "\u001B[1m";

    private static final List<Integer> historicoRequisicoes =
            Collections.synchronizedList(new ArrayList<>());

    private static final List<Double> historicoCpu =
            Collections.synchronizedList(new ArrayList<>());

    private static AlgoritmoReconstrucao.MatrizEsparsa H_60;
    private static AlgoritmoReconstrucao.MatrizEsparsa H_30;

    private static ThreadPoolExecutor executor;
    private static int contadorTarefas = 0;

    private static final OperatingSystemMXBean sistema =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private static class Tarefa {
        int id;
        String modelo;
        int ganho;
        String sinalData;
        Socket socket;

        Tarefa(int id, String modelo, int ganho, String sinalData, Socket socket) {
            this.id = id;
            this.modelo = modelo;
            this.ganho = ganho;
            this.sinalData = sinalData;
            this.socket = socket;
        }
    }

    private static void logTrabalhador(String mensagem) {
        logTrabalhador(mensagem, false);
    }

    private static void logTrabalhador(String mensagem, boolean erro) {
        String cor = erro ? COR_TRABALHADOR_ERRO : COR_TRABALHADOR;
        System.out.println(cor + NEGRITO + "[TRABALHADOR]" + RESET + " " + mensagem);
    }

    private static void logProcessador(String mensagem) {
        System.out.println(COR_PROCESSADOR + NEGRITO + "[PROCESSADOR]" + RESET + " " + mensagem);
    }

    private static void garantirPastaResultados() {
        File pasta = new File(PASTA_RESULTADOS);

        if (!pasta.exists()) {
            pasta.mkdirs();
        }
    }

    private static double medirCpuPercentual() {
        double carga = sistema.getCpuLoad();

        if (carga < 0.0) {
            return 0.0;
        }

        return carga * 100.0;
    }

    private static double medirMemoriaProcessoMb() {
        Runtime runtime = Runtime.getRuntime();

        long usada = runtime.totalMemory() - runtime.freeMemory();

        return usada / (1024.0 * 1024.0);
    }

    private static void inicializarMatrizes() {
        System.out.println("[*] Inicializando o Servidor: Carregando matrizes de modelo esparsas...");
        garantirPastaResultados();

        try {
            H_60 = AlgoritmoReconstrucao.carregarMatrizEsparsa("Cgnr/sinais/H-1.csv");
            H_30 = AlgoritmoReconstrucao.carregarMatrizEsparsa("Cgnr/sinais/H-2.csv");

            System.out.println("[*] Matrizes de modelo carregadas com sucesso de forma global!");
        } catch (Exception e) {
            System.out.println("[!] Erro crítico ao carregar modelos físicos: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static double[][] parseSinalMatriz(String sinalData) {
        String[] linhas = sinalData.trim().split("\\r?\\n");

        List<double[]> matriz = new ArrayList<>();

        for (String linha : linhas) {
            linha = linha.trim();

            if (linha.isEmpty()) {
                continue;
            }

            String[] partes = linha.split(";");

            double[] valores = new double[partes.length];

            for (int i = 0; i < partes.length; i++) {
                valores[i] = Double.parseDouble(partes[i].trim());
            }

            matriz.add(valores);
        }

        return matriz.toArray(new double[0][]);
    }

    private static double[][] aplicarGanhoSinal(double[][] gDados) {
        int S = gDados.length;

        if (S == 0) {
            return gDados;
        }

        int N = gDados[0].length;

        logProcessador("Aplicando ganho de sinal na matriz de dimensões: " + S + "x" + N);

        double[][] resultado = new double[S][N];

        for (int l = 0; l < S; l++) {
            int indiceL = l + 1;

            double gamma = 100.0 + (1.0 / 20.0) * indiceL * Math.sqrt(indiceL);

            for (int c = 0; c < N; c++) {
                resultado[l][c] = gDados[l][c] * gamma;
            }
        }

        return resultado;
    }

    private static double[] achatarMatriz(double[][] matriz) {
        int total = 0;

        for (double[] linha : matriz) {
            total += linha.length;
        }

        double[] vetor = new double[total];

        int pos = 0;

        for (double[] linha : matriz) {
            for (double valor : linha) {
                vetor[pos++] = valor;
            }
        }

        return vetor;
    }

    private static void salvarRelatorio(
            String idTarefa,
            String modelo,
            int ganho,
            double tempoExec,
            double cpuUso,
            double memUso
    ) {
        garantirPastaResultados();

        File arquivo = new File(PASTA_RESULTADOS, "relatorio_servidor_java.txt");
        boolean existe = arquivo.exists();

        try (PrintWriter writer = new PrintWriter(new FileWriter(arquivo, true))) {
            if (!existe) {
                writer.println("ID_TAREFA;MODELO;GANHO;TEMPO_EXEC_SEG;CPU_MED_PORCENTAGEM;MEM_MED_MB");
            }

            writer.printf(
                    Locale.US,
                    "%s;%s;%d;%.4f;%.2f;%.2f%n",
                    idTarefa,
                    modelo,
                    ganho,
                    tempoExec,
                    cpuUso,
                    memUso
            );

            System.out.println("[*] Estatísticas da tarefa " + idTarefa + " salvas em '" + arquivo.getPath() + "'");
        } catch (IOException e) {
            System.out.println("[!] Erro ao salvar relatório: " + e.getMessage());
        }
    }

    private static String montarRespostaSucesso(
            int idTarefa,
            String modelo,
            int ganho,
            AlgoritmoReconstrucao.Resultado resultado,
            String nomeImagem,
            double tempoTotal,
            double cpuFinal,
            double memGasta,
            int resolucao
    ) throws IOException {
        Path caminhoImagem = Paths.get(PASTA_RESULTADOS, nomeImagem);

        byte[] bytesImagem = Files.readAllBytes(caminhoImagem);
        String imagemB64 = Base64.getEncoder().encodeToString(bytesImagem);

        String stats = String.format(
                Locale.US,
                "id_tarefa=%d;algoritmo=%s;modelo=%s;ganho=%d;imagem=%s;" +
                        "tempo_total=%.6f;tempo_algoritmo=%.6f;iteracoes=%d;" +
                        "erro=%.12e;lambda=%.12e;cpu=%.2f;memoria_mb=%.2f;resolucao=%d",
                idTarefa,
                resultado.algoritmo,
                modelo,
                ganho,
                nomeImagem,
                tempoTotal,
                resultado.tempo,
                resultado.iteracoes,
                resultado.erro,
                resultado.lambda,
                cpuFinal,
                memGasta,
                resolucao
        );

        return "OK|" + stats + "|" + imagemB64;
    }

    private static void enviarRespostaCliente(Socket socket, String resposta) {
        if (socket == null) {
            return;
        }

        try {
            OutputStream saida = socket.getOutputStream();
            saida.write(resposta.getBytes(StandardCharsets.UTF_8));
            saida.flush();
        } catch (IOException e) {
            System.out.println("[!] Falha ao enviar resposta ao cliente: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void processarTarefa(Tarefa tarefa) {
        AlgoritmoReconstrucao.Resultado resultado;
        String nomeImagem;
        int resolucao;

        System.out.println("\n===========================================================================");
        logTrabalhador(
                "Iniciando Tarefa #" + tarefa.id +
                        " | Modelo: " + tarefa.modelo +
                        " | Ganho: " + tarefa.ganho +
                        " (1=ativo, 0=inativo)"
        );
        System.out.println("=============================================================================\n");

        long inicioNano = System.nanoTime();
        double cpuInicial = medirCpuPercentual();
        double memInicial = medirMemoriaProcessoMb();

        try {
            AlgoritmoReconstrucao.MatrizEsparsa HAtual;

            if (tarefa.modelo.contains("30")) {
                HAtual = H_30;
            } else {
                HAtual = H_60;
            }

            double[][] gDados = parseSinalMatriz(tarefa.sinalData);

            if (tarefa.ganho == 1) {
                gDados = aplicarGanhoSinal(gDados);
            }

            double[] gVetor = achatarMatriz(gDados);

            resultado = AlgoritmoReconstrucao.executarAlgoritmoAleatorio(HAtual, gVetor);

            resolucao = AlgoritmoReconstrucao.descobrirResolucao(HAtual);
            nomeImagem = "imagem_tarefa_" + tarefa.id + "_" + tarefa.modelo + "_" + resultado.algoritmo + ".png";

            AlgoritmoReconstrucao.salvarImagem(resultado.f, resolucao, nomeImagem);

        } catch (Exception e) {
            logTrabalhador("Erro na Tarefa #" + tarefa.id + ": " + e.getMessage(), true);
            enviarRespostaCliente(tarefa.socket, "ERRO|id_tarefa=" + tarefa.id + ";mensagem=" + e.getMessage());
            return;
        }

        long fimNano = System.nanoTime();
        double tempoTotal = (fimNano - inicioNano) / 1_000_000_000.0;

        double cpuFinal = medirCpuPercentual();
        double memFinal = medirMemoriaProcessoMb();

        double cpuMedio = Math.max(cpuFinal - cpuInicial, 1.0);
        double memGasta = Math.max(memFinal - memInicial, 0.1);

        System.out.println("\n==================================================");
        logTrabalhador(
                String.format(
                        Locale.US,
                        "Imagem salva em '%s/%s' usando %s em %d iterações | " +
                                "Tempo total: %.2fs | Tempo algoritmo: %.4fs | " +
                                "Erro: %.6e | λ(lambda): %.6e | CPU: %.1f%% | Memória: %.1f MB | " +
                                "Resolução: %dx%d | Modelo: %s | Ganho: %d",
                        PASTA_RESULTADOS,
                        nomeImagem,
                        resultado.algoritmo,
                        resultado.iteracoes,
                        tempoTotal,
                        resultado.tempo,
                        resultado.erro,
                        resultado.lambda,
                        cpuFinal,
                        memGasta,
                        resolucao,
                        resolucao,
                        tarefa.modelo,
                        tarefa.ganho
                )
        );
        System.out.println("====================================================\n");

        salvarRelatorio(
                tarefa.id + "_" + resultado.algoritmo,
                tarefa.modelo,
                tarefa.ganho,
                tempoTotal,
                cpuMedio,
                memGasta
        );

        try {
            String resposta = montarRespostaSucesso(
                    tarefa.id,
                    tarefa.modelo,
                    tarefa.ganho,
                    resultado,
                    nomeImagem,
                    tempoTotal,
                    cpuFinal,
                    memGasta,
                    resolucao
            );

            enviarRespostaCliente(tarefa.socket, resposta);
        } catch (IOException e) {
            enviarRespostaCliente(tarefa.socket, "ERRO|id_tarefa=" + tarefa.id + ";mensagem=" + e.getMessage());
        }
    }

    private static String receberPayload(Socket socket) throws IOException {
        InputStream entrada = socket.getInputStream();
        ByteArrayOutputStream dados = new ByteArrayOutputStream();

        byte[] buffer = new byte[TAM_BUFFER];
        int lidos;

        while ((lidos = entrada.read(buffer)) != -1) {
            dados.write(buffer, 0, lidos);
        }

        return dados.toString(StandardCharsets.UTF_8);
    }

    private static void gerarGraficoDesempenho() {
        if (historicoRequisicoes.isEmpty()) {
            System.out.println("[*] Nenhuma requisição registrada para gerar gráfico.");
            return;
        }

        garantirPastaResultados();

        int largura = 1000;
        int altura = 500;
        int margemEsquerda = 70;
        int margemDireita = 30;
        int margemTopo = 50;
        int margemBaixo = 70;

        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagem.createGraphics();

        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, largura, altura);

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int x0 = margemEsquerda;
            int y0 = altura - margemBaixo;
            int x1 = largura - margemDireita;
            int y1 = margemTopo;

            g.setColor(Color.BLACK);
            g.drawLine(x0, y0, x1, y0);
            g.drawLine(x0, y0, x0, y1);

            g.setFont(new Font("Arial", Font.BOLD, 18));
            g.drawString("Desempenho da CPU em Função das Requisições Gerais", 210, 30);

            g.setFont(new Font("Arial", Font.PLAIN, 13));
            g.drawString("ID da Requisição (Ordem de Chegada)", 390, altura - 20);
            g.drawString("Uso da CPU (%)", 10, 35);

            for (int i = 0; i <= 100; i += 20) {
                int y = y0 - (int) ((i / 105.0) * (y0 - y1));
                g.setColor(new Color(220, 220, 220));
                g.drawLine(x0, y, x1, y);

                g.setColor(Color.BLACK);
                g.drawString(String.valueOf(i), x0 - 35, y + 5);
            }

            int yLimite = y0 - (int) ((LIMITE_CPU / 105.0) * (y0 - y1));
            g.setColor(Color.RED);
            g.drawLine(x0, yLimite, x1, yLimite);
            g.drawString("Limite de Saturação (" + LIMITE_CPU + "%)", x1 - 220, yLimite - 8);

            List<Integer> reqs;
            List<Double> cpus;

            synchronized (historicoRequisicoes) {
                reqs = new ArrayList<>(historicoRequisicoes);
            }

            synchronized (historicoCpu) {
                cpus = new ArrayList<>(historicoCpu);
            }

            int n = reqs.size();

            if (n == 1) {
                int x = x0 + (x1 - x0) / 2;
                int y = y0 - (int) ((cpus.get(0) / 105.0) * (y0 - y1));

                g.setColor(Color.BLUE);
                g.fillOval(x - 4, y - 4, 8, 8);
            } else {
                int xAnterior = 0;
                int yAnterior = 0;

                g.setColor(Color.BLUE);

                for (int i = 0; i < n; i++) {
                    int x = x0 + (int) ((i / (double) (n - 1)) * (x1 - x0));
                    int y = y0 - (int) ((cpus.get(i) / 105.0) * (y0 - y1));

                    g.fillOval(x - 4, y - 4, 8, 8);

                    if (i > 0) {
                        g.drawLine(xAnterior, yAnterior, x, y);
                    }

                    xAnterior = x;
                    yAnterior = y;
                }
            }

            File arquivo = new File(PASTA_RESULTADOS, "GRAFICO.png");
            ImageIO.write(imagem, "png", arquivo);

            System.out.println("\n[+] Gráfico de desempenho salvo com sucesso em '" + arquivo.getPath() + "'!");

        } catch (IOException e) {
            System.out.println("[!] Erro ao salvar gráfico: " + e.getMessage());
        } finally {
            g.dispose();
        }
    }

    public static void iniciarServidor() {
        executor = new ThreadPoolExecutor(
                MAX_TRABALHADORES,
                MAX_TRABALHADORES,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()
        );

        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(HOST, PORTA), 10);

            System.out.println("\n===================================================");
            System.out.println("[*] Servidor Java Multi-Thread Ativo na porta " + PORTA);
            System.out.println("[*] Controle de Saturação: Limite de " + MAX_TRABALHADORES + " tarefas simultâneas.");
            System.out.println("[*] Limite da CPU: " + LIMITE_CPU + " %");
            System.out.println("=====================================================\n");

            while (true) {
                Socket clientSocket = serverSocket.accept();

                double cpuAtual = medirCpuPercentual();

                contadorTarefas++;
                historicoRequisicoes.add(contadorTarefas);
                historicoCpu.add(cpuAtual);

                if (cpuAtual >= LIMITE_CPU) {
                    System.out.println("[!] REQUISIÇÃO RECUSADA: CPU em " + cpuAtual + "%. Servidor temporariamente indisponível.");

                    OutputStream saida = clientSocket.getOutputStream();
                    saida.write("Erro: Servidor sobrecarregado. Tente novamente mais tarde.".getBytes(StandardCharsets.UTF_8));
                    saida.flush();
                    clientSocket.close();
                    continue;
                }

                System.out.println("\n[*] Conexão de rede vinda de " +
                        clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());

                String mensagem = receberPayload(clientSocket);

                if (!mensagem.isEmpty()) {
                    String modelo;
                    String ganhoTexto;
                    String sinalData;

                    try {
                        String[] partesPayload = mensagem.split("\\|", 2);
                        String cabecalho = partesPayload[0];
                        sinalData = partesPayload[1];

                        String[] partesCabecalho = cabecalho.split(";", 2);
                        modelo = partesCabecalho[0];
                        ganhoTexto = partesCabecalho[1];
                    } catch (Exception e) {
                        modelo = "Desconhecido";
                        ganhoTexto = "0";
                        sinalData = mensagem;
                    }

                    int ganho;

                    try {
                        ganho = Integer.parseInt(ganhoTexto.trim());
                    } catch (NumberFormatException e) {
                        ganho = 0;
                    }

                    Tarefa tarefa = new Tarefa(
                            contadorTarefas,
                            modelo,
                            ganho,
                            sinalData,
                            clientSocket
                    );

                    int posicaoFila = executor.getQueue().size() + 1;

                    System.out.println("[+] Tarefa #" + contadorTarefas +
                            " adicionada à fila. Posição atual: " + posicaoFila);

                    executor.submit(() -> processarTarefa(tarefa));
                } else {
                    clientSocket.close();
                }
            }

        } catch (IOException e) {
            System.out.println("[!] Erro no servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[*] Garantindo salvamento dos dados analíticos...");
            gerarGraficoDesempenho();

            if (executor != null) {
                executor.shutdownNow();
            }
        }));

        inicializarMatrizes();

        iniciarServidor();
    }
}
