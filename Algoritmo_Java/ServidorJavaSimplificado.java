package Algoritmo_Java;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import com.sun.management.OperatingSystemMXBean;

public class ServidorJava {

    private static final String HOST = "127.0.0.1";
    private static final int PORTA = 8000;
    private static final int TAM_BUFFER = 4096;

    private static final int MAX_TRABALHADORES = 8;
    private static final double LIMITE_CPU = 96.0;

    private static final String PASTA_RESULTADOS = "resultados-relatorio";

    private static AlgoritmoReconstrucao.MatrizEsparsa H_60;
    private static AlgoritmoReconstrucao.MatrizEsparsa H_30;

    private static int contadorTarefas = 0;

    private static ThreadPoolExecutor executor;

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

    private static void garantirPastaResultados() {
        File pasta = new File(PASTA_RESULTADOS);

        if (!pasta.exists()) {
            pasta.mkdirs();
        }
    }

    private static double medirCpuPercentual() {
        double carga = sistema.getSystemCpuLoad();

        if (carga < 0.0) {
            return 0.0;
        }

        return carga * 100.0;
    }

    private static double medirMemoriaMb() {
        Runtime runtime = Runtime.getRuntime();
        long usada = runtime.totalMemory() - runtime.freeMemory();

        return usada / (1024.0 * 1024.0);
    }

    private static void carregarMatrizesGlobais() {
        System.out.println("[*] Inicializando servidor Java: carregando matrizes H...");

        garantirPastaResultados();

        try {
            H_60 = AlgoritmoReconstrucao.carregarMatrizEsparsa("Cgnr/sinais/H-1.csv");
            H_30 = AlgoritmoReconstrucao.carregarMatrizEsparsa("Cgnr/sinais/H-2.csv");

            System.out.println("[*] Matrizes carregadas com sucesso.");
        } catch (Exception e) {
            System.out.println("[!] Erro crítico ao carregar matrizes: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
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

    private static void enviarResposta(Socket socket, String resposta) {
        try {
            OutputStream saida = socket.getOutputStream();
            saida.write(resposta.getBytes(StandardCharsets.UTF_8));
            saida.flush();
        } catch (IOException e) {
            System.out.println("[!] Erro ao enviar resposta: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static double[][] parseSinalMatriz(String sinalData) {
        String[] linhas = sinalData.trim().split("\\r?\\n");

        double[][] matriz = new double[linhas.length][];

        int qtdLinhasValidas = 0;

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

            matriz[qtdLinhasValidas] = valores;
            qtdLinhasValidas++;
        }

        if (qtdLinhasValidas == matriz.length) {
            return matriz;
        }

        double[][] ajustada = new double[qtdLinhasValidas][];
        System.arraycopy(matriz, 0, ajustada, 0, qtdLinhasValidas);

        return ajustada;
    }

    private static double[][] aplicarGanhoSinal(double[][] gDados) {
        int s = gDados.length;

        if (s == 0) {
            return gDados;
        }

        int n = gDados[0].length;

        System.out.println("[PROCESSADOR] Aplicando ganho na matriz " + s + "x" + n);

        double[][] resultado = new double[s][n];

        for (int l = 0; l < s; l++) {
            int indiceL = l + 1;

            double gamma = 100.0 + (1.0 / 20.0) * indiceL * Math.sqrt(indiceL);

            for (int c = 0; c < n; c++) {
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
                vetor[pos] = valor;
                pos++;
            }
        }

        return vetor;
    }

    private static void salvarRelatorio(
            String idTarefa,
            String modelo,
            int ganho,
            double tempoTotal,
            double cpu,
            double memoriaMb
    ) {
        garantirPastaResultados();

        File arquivo = new File(PASTA_RESULTADOS, "relatorio_servidor_java.txt");
        boolean existe = arquivo.exists();

        try (PrintWriter writer = new PrintWriter(new FileWriter(arquivo, true))) {
            if (!existe) {
                writer.println("ID_TAREFA;MODELO;GANHO;TEMPO_EXEC_SEG;CPU_PORCENTAGEM;MEM_MB");
            }

            writer.printf(
                    Locale.US,
                    "%s;%s;%d;%.4f;%.2f;%.2f%n",
                    idTarefa,
                    modelo,
                    ganho,
                    tempoTotal,
                    cpu,
                    memoriaMb
            );
        } catch (IOException e) {
            System.out.println("[!] Erro ao salvar relatório: " + e.getMessage());
        }
    }

    private static void processarTarefa(Tarefa tarefa) {
        long inicio = System.nanoTime();

        System.out.println();
        System.out.println("[TRABALHADOR] Iniciando tarefa #" + tarefa.id +
                " | Modelo=" + tarefa.modelo +
                " | Ganho=" + tarefa.ganho);

        try {
            AlgoritmoReconstrucao.MatrizEsparsa HAtual;

            if (tarefa.modelo.contains("30")) {
                HAtual = H_30;
            } else {
                HAtual = H_60;
            }

            double[][] gMatriz = parseSinalMatriz(tarefa.sinalData);

            if (tarefa.ganho == 1) {
                gMatriz = aplicarGanhoSinal(gMatriz);
            }

            double[] gVetor = achatarMatriz(gMatriz);

            AlgoritmoReconstrucao.Resultado resultado =
                    AlgoritmoReconstrucao.executarAlgoritmoAleatorio(HAtual, gVetor);

            int resolucao = AlgoritmoReconstrucao.descobrirResolucao(HAtual);

            String nomeImagem = "imagem_tarefa_" +
                    tarefa.id + "_" +
                    tarefa.modelo + "_" +
                    resultado.algoritmo + ".png";

            AlgoritmoReconstrucao.salvarImagem(resultado.f, resolucao, nomeImagem);

            long fim = System.nanoTime();

            double tempoTotal = (fim - inicio) / 1_000_000_000.0;
            double cpuFinal = medirCpuPercentual();
            double memoriaMb = medirMemoriaMb();

            salvarRelatorio(
                    tarefa.id + "_" + resultado.algoritmo,
                    tarefa.modelo,
                    tarefa.ganho,
                    tempoTotal,
                    cpuFinal,
                    memoriaMb
            );

            String resposta = String.format(
                    Locale.US,
                    "OK|id_tarefa=%d;algoritmo=%s;modelo=%s;ganho=%d;imagem=%s;" +
                            "tempo_total=%.6f;tempo_algoritmo=%.6f;iteracoes=%d;" +
                            "erro=%.12e;lambda=%.12e;cpu=%.2f;memoria_mb=%.2f;resolucao=%d",
                    tarefa.id,
                    resultado.algoritmo,
                    tarefa.modelo,
                    tarefa.ganho,
                    nomeImagem,
                    tempoTotal,
                    resultado.tempo,
                    resultado.iteracoes,
                    resultado.erro,
                    resultado.lambda,
                    cpuFinal,
                    memoriaMb,
                    resolucao
            );

            System.out.println("[TRABALHADOR] Tarefa #" + tarefa.id + " finalizada.");
            System.out.println("[TRABALHADOR] Imagem salva em " + PASTA_RESULTADOS + File.separator + nomeImagem);
            System.out.println("[TRABALHADOR] " + resposta);

            enviarResposta(tarefa.socket, resposta);

        } catch (Exception e) {
            System.out.println("[TRABALHADOR] Erro na tarefa #" + tarefa.id + ": " + e.getMessage());
            enviarResposta(tarefa.socket, "ERRO|id_tarefa=" + tarefa.id + ";mensagem=" + e.getMessage());
        }
    }

    private static void iniciarServidor() {
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(MAX_TRABALHADORES);

        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(HOST, PORTA), 10);

            System.out.println();
            System.out.println("===================================================");
            System.out.println("[*] Servidor Java Multi-Thread ativo na porta " + PORTA);
            System.out.println("[*] Trabalhadores simultâneos: " + MAX_TRABALHADORES);
            System.out.println("[*] Limite de CPU: " + LIMITE_CPU + "%");
            System.out.println("===================================================");
            System.out.println();

            while (true) {
                Socket clientSocket = serverSocket.accept();

                double cpuAtual = medirCpuPercentual();

                contadorTarefas++;

                if (cpuAtual >= LIMITE_CPU) {
                    System.out.println("[!] Requisição recusada. CPU=" + cpuAtual + "%");

                    enviarResposta(
                            clientSocket,
                            "ERRO|servidor_sobrecarregado;cpu=" + String.format(Locale.US, "%.2f", cpuAtual)
                    );

                    continue;
                }

                System.out.println("[*] Cliente conectado: " +
                        clientSocket.getInetAddress().getHostAddress() + ":" +
                        clientSocket.getPort());

                String mensagem = receberPayload(clientSocket);

                if (mensagem.isEmpty()) {
                    clientSocket.close();
                    continue;
                }

                String modelo;
                int ganho;
                String sinalData;

                try {
                    String[] partesPayload = mensagem.split("\\|", 2);
                    String cabecalho = partesPayload[0];
                    sinalData = partesPayload[1];

                    String[] partesCabecalho = cabecalho.split(";", 2);
                    modelo = partesCabecalho[0];
                    ganho = Integer.parseInt(partesCabecalho[1].trim());
                } catch (Exception e) {
                    enviarResposta(clientSocket, "ERRO|formato_invalido;esperado=MODELO;GANHO|SINAL");
                    continue;
                }

                Tarefa tarefa = new Tarefa(
                        contadorTarefas,
                        modelo,
                        ganho,
                        sinalData,
                        clientSocket
                );

                int posicaoFila = executor.getQueue().size() + 1;

                System.out.println("[+] Tarefa #" + tarefa.id +
                        " adicionada à fila. Posição atual: " + posicaoFila);

                executor.submit(() -> processarTarefa(tarefa));
            }

        } catch (IOException e) {
            System.out.println("[!] Erro no servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        carregarMatrizesGlobais();
        iniciarServidor();
    }
}
