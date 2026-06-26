package Algoritmo_Java;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.Random;

public class AlgoritmoReconstrucao {

    public static final String PASTA_RESULTADOS = "resultados-relatorio";

    private static void garantirPastaResultados() {
        File pasta = new File(PASTA_RESULTADOS);

        if (!pasta.exists()) {
            pasta.mkdirs();
        }
    }

    public static class MatrizEsparsa {
        public final int linhas;
        public final int colunas;
        public final int nnz;
        public final int[] rowPtr;
        public final int[] colIdx;
        public final double[] valores;

        public MatrizEsparsa(int linhas, int colunas, int nnz, int[] rowPtr, int[] colIdx, double[] valores) {
            this.linhas = linhas;
            this.colunas = colunas;
            this.nnz = nnz;
            this.rowPtr = rowPtr;
            this.colIdx = colIdx;
            this.valores = valores;
        }
    }

    public static class Resultado {
        public double[] f;
        public double tempo;
        public int iteracoes;
        public double erro;
        public double erroAbs;
        public double lambda;
        public String algoritmo;

        public Resultado(double[] f, double tempo, int iteracoes, double erro, double lambda, String algoritmo) {
            this.f = f;
            this.tempo = tempo;
            this.iteracoes = iteracoes;
            this.erro = erro;
            this.erroAbs = Math.abs(erro);
            this.lambda = lambda;
            this.algoritmo = algoritmo;
        }
    }

    public static MatrizEsparsa carregarMatrizEsparsa(String nomeArquivo) throws IOException {
        String cache = nomeArquivo + ".java.bin";

        File arquivoCache = new File(cache);

        if (arquivoCache.exists()) {
            System.out.println("Carregando matriz esparsa do cache " + cache + "...");
            return carregarMatrizCache(cache);
        }

        System.out.println("Convertendo CSV " + nomeArquivo + " para matriz esparsa...");

        int capacidade = 1024;
        int nnz = 0;
        int linhas = 0;
        int colunas = 0;

        int[] linhasCoo = new int[capacidade];
        int[] colunasCoo = new int[capacidade];
        double[] valoresCoo = new double[capacidade];

        try (BufferedReader br = new BufferedReader(new FileReader(nomeArquivo))) {
            String linha;

            while ((linha = br.readLine()) != null) {
                String[] partes = linha.split(",", -1);

                if (partes.length > colunas) {
                    colunas = partes.length;
                }

                for (int j = 0; j < partes.length; j++) {
                    String valorStr = partes[j].trim();

                    if (valorStr.isEmpty()) {
                        continue;
                    }

                    double valor = Double.parseDouble(valorStr);

                    if (valor != 0.0) {
                        if (nnz >= capacidade) {
                            capacidade *= 2;

                            int[] novasLinhas = new int[capacidade];
                            int[] novasColunas = new int[capacidade];
                            double[] novosValores = new double[capacidade];

                            System.arraycopy(linhasCoo, 0, novasLinhas, 0, nnz);
                            System.arraycopy(colunasCoo, 0, novasColunas, 0, nnz);
                            System.arraycopy(valoresCoo, 0, novosValores, 0, nnz);

                            linhasCoo = novasLinhas;
                            colunasCoo = novasColunas;
                            valoresCoo = novosValores;
                        }

                        linhasCoo[nnz] = linhas;
                        colunasCoo[nnz] = j;
                        valoresCoo[nnz] = valor;
                        nnz++;
                    }
                }

                linhas++;
            }
        }

        int[] rowPtr = new int[linhas + 1];
        int[] colIdx = new int[nnz];
        double[] valores = new double[nnz];

        for (int k = 0; k < nnz; k++) {
            rowPtr[linhasCoo[k] + 1]++;
        }

        for (int i = 0; i < linhas; i++) {
            rowPtr[i + 1] += rowPtr[i];
        }

        int[] posicoes = new int[linhas];
        System.arraycopy(rowPtr, 0, posicoes, 0, linhas);

        for (int k = 0; k < nnz; k++) {
            int linhaAtual = linhasCoo[k];
            int destino = posicoes[linhaAtual]++;

            colIdx[destino] = colunasCoo[k];
            valores[destino] = valoresCoo[k];
        }

        MatrizEsparsa H = new MatrizEsparsa(linhas, colunas, nnz, rowPtr, colIdx, valores);

        salvarMatrizCache(cache, H);

        System.out.println("Matriz esparsa salva em cache: (" + H.linhas + ", " + H.colunas + "), nnz=" + H.nnz);

        return H;
    }

    private static void salvarMatrizCache(String cache, MatrizEsparsa H) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(cache)))) {
            dos.writeInt(H.linhas);
            dos.writeInt(H.colunas);
            dos.writeInt(H.nnz);

            for (int valor : H.rowPtr) {
                dos.writeInt(valor);
            }

            for (int valor : H.colIdx) {
                dos.writeInt(valor);
            }

            for (double valor : H.valores) {
                dos.writeDouble(valor);
            }
        }
    }

    private static MatrizEsparsa carregarMatrizCache(String cache) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(cache)))) {
            int linhas = dis.readInt();
            int colunas = dis.readInt();
            int nnz = dis.readInt();

            int[] rowPtr = new int[linhas + 1];
            int[] colIdx = new int[nnz];
            double[] valores = new double[nnz];

            for (int i = 0; i < rowPtr.length; i++) {
                rowPtr[i] = dis.readInt();
            }

            for (int i = 0; i < colIdx.length; i++) {
                colIdx[i] = dis.readInt();
            }

            for (int i = 0; i < valores.length; i++) {
                valores[i] = dis.readDouble();
            }

            return new MatrizEsparsa(linhas, colunas, nnz, rowPtr, colIdx, valores);
        }
    }

    public static double[] carregarCsv(String nomeArquivo) throws IOException {
        System.out.println("Lendo CSV " + nomeArquivo + "...");

        int capacidade = 1024;
        int n = 0;
        double[] dados = new double[capacidade];

        try (BufferedReader br = new BufferedReader(new FileReader(nomeArquivo))) {
            String linha;

            while ((linha = br.readLine()) != null) {
                String[] partes = linha.split("[,;]");

                for (String parte : partes) {
                    parte = parte.trim();

                    if (parte.isEmpty()) {
                        continue;
                    }

                    if (n >= capacidade) {
                        capacidade *= 2;

                        double[] novo = new double[capacidade];
                        System.arraycopy(dados, 0, novo, 0, n);
                        dados = novo;
                    }

                    dados[n++] = Double.parseDouble(parte);
                }
            }
        }

        double[] resultado = new double[n];
        System.arraycopy(dados, 0, resultado, 0, n);

        System.out.println("CSV carregado: " + resultado.length + " elementos");

        return resultado;
    }
    public static double[][] carregarCsvMatriz(String nomeArquivo) throws IOException {
        System.out.println("Lendo CSV como matriz " + nomeArquivo + "...");

        java.util.ArrayList<double[]> linhas = new java.util.ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(nomeArquivo))) {
            String linha;

            while ((linha = br.readLine()) != null) {
                linha = linha.trim();

                if (linha.isEmpty()) {
                    continue;
                }

                String[] partes = linha.split("[,;]");
                double[] valores = new double[partes.length];

                for (int i = 0; i < partes.length; i++) {
                    valores[i] = Double.parseDouble(partes[i].trim());
                }

                linhas.add(valores);
            }
        }

        return linhas.toArray(new double[0][]);
    }

    public static double[][] aplicarGanhoSinal(double[][] gDados) {
        int S = gDados.length;

        if (S == 0) {
            return gDados;
        }

        int N = gDados[0].length;

        System.out.println("Aplicando ganho no sinal: " + S + "x" + N);

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

    public static double[] achatarMatriz(double[][] matriz) {
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
    public static double[] parseSinalRecebido(String sinalData) {
        String[] partes = sinalData.split("[,;\\r\\n]+");

        double[] temporario = new double[partes.length];
        int n = 0;

        for (String parte : partes) {
            parte = parte.trim();

            if (!parte.isEmpty()) {
                temporario[n++] = Double.parseDouble(parte);
            }
        }

        double[] resultado = new double[n];
        System.arraycopy(temporario, 0, resultado, 0, n);

        return resultado;
    }

    private static double dot(double[] a, double[] b) {
        double soma = 0.0;

        for (int i = 0; i < a.length; i++) {
            soma += a[i] * b[i];
        }

        return soma;
    }

    private static double norma2(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    private static double[] matVec(MatrizEsparsa H, double[] x) {
        double[] y = new double[H.linhas];

        for (int i = 0; i < H.linhas; i++) {
            double soma = 0.0;

            for (int k = H.rowPtr[i]; k < H.rowPtr[i + 1]; k++) {
                soma += H.valores[k] * x[H.colIdx[k]];
            }

            y[i] = soma;
        }

        return y;
    }

    private static double[] transpostaMatVec(MatrizEsparsa H, double[] x) {
        double[] y = new double[H.colunas];

        for (int i = 0; i < H.linhas; i++) {
            for (int k = H.rowPtr[i]; k < H.rowPtr[i + 1]; k++) {
                int j = H.colIdx[k];
                y[j] += H.valores[k] * x[i];
            }
        }

        return y;
    }

    private static double[] somaVetores(double[] a, double[] b) {
        double[] resultado = new double[a.length];

        for (int i = 0; i < a.length; i++) {
            resultado[i] = a[i] + b[i];
        }

        return resultado;
    }

    private static double[] subtraiVetores(double[] a, double[] b) {
        double[] resultado = new double[a.length];

        for (int i = 0; i < a.length; i++) {
            resultado[i] = a[i] - b[i];
        }

        return resultado;
    }

    private static double[] multiplicaEscalar(double[] v, double escalar) {
        double[] resultado = new double[v.length];

        for (int i = 0; i < v.length; i++) {
            resultado[i] = v[i] * escalar;
        }

        return resultado;
    }

    public static double calcularCoeficienteRegularizacao(MatrizEsparsa H, double[] g) {
        double[] htg = transpostaMatVec(H, g);

        double max = 0.0;

        for (double valor : htg) {
            double abs = Math.abs(valor);

            if (abs > max) {
                max = abs;
            }
        }

        return max * 0.10;
    }

    public static Resultado cgnr(MatrizEsparsa H, double[] g) {
        return cgnr(H, g, 1e-4, 10);
    }

    public static Resultado cgnr(MatrizEsparsa H, double[] g, double tol, int maxIter) {
        long inicio = System.nanoTime();

        if (H.linhas != g.length) {
            throw new IllegalArgumentException(
                "Dimensões incompatíveis: H tem " + H.linhas +
                " linhas, mas g tem " + g.length + " elementos."
            );
        }

        double lambda = calcularCoeficienteRegularizacao(H, g);

        double[] f = new double[H.colunas];

        double[] r = g.clone();
        double[] z = transpostaMatVec(H, r);
        double[] p = z.clone();

        double normZSq = dot(z, z);

        double erro = Double.POSITIVE_INFINITY;
        int iteracoes = 0;

        for (int i = 0; i < maxIter; i++) {
            iteracoes = i + 1;

            double[] w = matVec(H, p);
            double normWSq = dot(w, w);

            if (normWSq == 0.0) {
                break;
            }

            double alpha = normZSq / normWSq;

            f = somaVetores(f, multiplicaEscalar(p, alpha));
            r = subtraiVetores(r, multiplicaEscalar(w, alpha));

            erro = norma2(r);

            if (erro < tol) {
                break;
            }

            double[] zNovo = transpostaMatVec(H, r);
            double normZNovoSq = dot(zNovo, zNovo);

            if (normZSq == 0.0) {
                break;
            }

            double beta = normZNovoSq / normZSq;

            p = somaVetores(zNovo, multiplicaEscalar(p, beta));

            z = zNovo;
            normZSq = normZNovoSq;
        }

        long fim = System.nanoTime();

        double tempo = (fim - inicio) / 1_000_000_000.0;

        return new Resultado(f, tempo, iteracoes, erro, lambda, "CGNR");
    }

    public static Resultado cgne(MatrizEsparsa H, double[] g) {
        return cgne(H, g, 1e-4, 10);
    }

    public static Resultado cgne(MatrizEsparsa H, double[] g, double tol, int maxIter) {
        long inicio = System.nanoTime();

        if (H.linhas != g.length) {
            throw new IllegalArgumentException(
                "Dimensões incompatíveis: H tem " + H.linhas +
                " linhas, mas g tem " + g.length + " elementos."
            );
        }

        double lambda = calcularCoeficienteRegularizacao(H, g);

        double[] f = new double[H.colunas];

        double[] r = g.clone();
        double[] p = transpostaMatVec(H, r);

        double erro = Double.POSITIVE_INFINITY;
        int iteracoes = 0;

        for (int i = 0; i < maxIter; i++) {
            iteracoes = i + 1;

            double normRSq = dot(r, r);
            double normPSq = dot(p, p);

            if (normPSq == 0.0) {
                break;
            }

            double alpha = normRSq / normPSq;

            f = somaVetores(f, multiplicaEscalar(p, alpha));

            double[] hp = matVec(H, p);
            r = subtraiVetores(r, multiplicaEscalar(hp, alpha));

            erro = norma2(r);

            if (erro < tol) {
                break;
            }

            double normRNewSq = dot(r, r);

            if (normRSq == 0.0) {
                break;
            }

            double beta = normRNewSq / normRSq;

            p = somaVetores(transpostaMatVec(H, r), multiplicaEscalar(p, beta));
        }

        long fim = System.nanoTime();

        double tempo = (fim - inicio) / 1_000_000_000.0;

        return new Resultado(f, tempo, iteracoes, erro, lambda, "CGNE");
    }

    public static void salvarImagem(double[] f, int resolucao, String nomeArquivo) throws IOException {
        garantirPastaResultados();

        String caminho = PASTA_RESULTADOS + File.separator + new File(nomeArquivo).getName();

        int largura = resolucao;
        int altura = resolucao;

        if (f.length != largura * altura) {
            throw new IllegalArgumentException(
                "Vetor f tem " + f.length + " elementos, mas esperado " + (largura * altura)
            );
        }

        double fMin = f[0];
        double fMax = f[0];

        for (double valor : f) {
            if (valor < fMin) {
                fMin = valor;
            }

            if (valor > fMax) {
                fMax = valor;
            }
        }

        double fRange = fMax - fMin;

        if (fRange == 0.0) {
            fRange = 1.0;
        }

        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 0; y < altura; y++) {
            for (int x = 0; x < largura; x++) {
                int indice = x * altura + y;

                int cinza = (int)(((f[indice] - fMin) / fRange) * 255.0);

                if (cinza < 0) {
                    cinza = 0;
                }

                if (cinza > 255) {
                    cinza = 255;
                }

                int rgb = (cinza << 16) | (cinza << 8) | cinza;
                imagem.setRGB(x, y, rgb);
            }
        }

        ImageIO.write(imagem, "png", new File(caminho));
    }

    public static int descobrirResolucao(MatrizEsparsa H) {
        int pixels = H.colunas;
        int resolucao = (int)Math.sqrt(pixels);

        if (resolucao * resolucao != pixels) {
            throw new IllegalArgumentException(
                "H tem " + pixels + " colunas, não forma uma imagem quadrada."
            );
        }

        return resolucao;
    }

    public static Resultado executarAlgoritmoAleatorio(MatrizEsparsa H, double[] g) {
        Random random = new Random();

        if (random.nextBoolean()) {
            return cgnr(H, g);
        }

        return cgne(H, g);
    }

    public static void main(String[] args) {
        try {
            int resolucaoDesejada = 60;
            String algoritmo = "CGNR";
            int versaoTeste = 1;
            boolean usarGanho = false;

            System.out.println("Carregando H e g...");

            MatrizEsparsa H;
            double[] g;

            String caminhoG;

            if (resolucaoDesejada == 60) {
                H = carregarMatrizEsparsa("Cgnr/sinais/H-1.csv");
                caminhoG = "Cgnr/sinais/G-" + versaoTeste + ".csv";
            } else if (resolucaoDesejada == 30) {
                H = carregarMatrizEsparsa("Cgnr/sinais/H-2.csv");
                caminhoG =  "Cgnr/sinais/g-30x30-" + versaoTeste + ".csv";
            } else {
                throw new IllegalArgumentException("Algoritmo inválido. Use CGNR ou CGNE.");
            }
            if (usarGanho) {
                double[][] gMatriz = carregarCsvMatriz(caminhoG);
                gMatriz = aplicarGanhoSinal(gMatriz);
                g = achatarMatriz(gMatriz);
            } else {
                g = carregarCsv(caminhoG);
            }

            int resolucao = descobrirResolucao(H);

            System.out.println("Resolução: " + resolucao + "x" + resolucao);
            System.out.println("H: (" + H.linhas + ", " + H.colunas + ")");
            System.out.println("g: " + g.length + " elementos");

            System.out.println("Rodando " + algoritmo + "...");

            long inicioTotal = System.nanoTime();

            Resultado resultado;

            if (algoritmo.equalsIgnoreCase("CGNR")) {
                resultado = cgnr(H, g);
            } else if (algoritmo.equalsIgnoreCase("CGNE")) {
                resultado = cgne(H, g);
            } else {
                throw new IllegalArgumentException("Algoritmo inválido. Use CGNR ou CGNE.");
            }

            long fimTotal = System.nanoTime();
            double tempoTotal = (fimTotal - inicioTotal) / 1_000_000_000.0;

            String nomeImagem = "imagem_reconstruida_" 
                + resolucao + "x" + resolucao 
                + "_" + algoritmo 
                + "_teste" + versaoTeste
                + "_ganho" + (usarGanho ? "1" : "0")
                + ".png";

            salvarImagem(resultado.f, resolucao, nomeImagem);

            System.out.println("Tempo algoritmo: " + resultado.tempo);
            System.out.println("Tempo total: " + tempoTotal);
            System.out.println("Iterações: " + resultado.iteracoes);
            System.out.println("Erro: " + resultado.erro);
            System.out.println("Lambda: " + resultado.lambda);
            System.out.println("Imagem salva em: " + PASTA_RESULTADOS + File.separator + nomeImagem);
            System.out.println("Finalizado.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
