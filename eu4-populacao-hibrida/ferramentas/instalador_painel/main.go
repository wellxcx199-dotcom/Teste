// Instalador do painel de província da População Híbrida.
//
// Faz o mesmo que ferramentas/instalar_painel_provincia.py, mas como um
// .exe que não precisa de Python: acha sozinho a pasta do EU4 e o mod
// instalado, insere a linha de demografia na cópia do provinceview.gui e
// grava o resultado no mod junto com a definição do texto (custom_gui).
//
// Para gerar o .exe (de qualquer sistema):
//
//	GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o INSTALAR_PAINEL.exe .
package main

import (
	"bufio"
	_ "embed"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

//go:embed moldes/painel_provincia.gui
var moldeGui []byte

//go:embed moldes/painel_provincia_custom_gui.txt
var moldeCustomGui []byte

const pastaEU4NosDocumentos = `Paradox Interactive/Europa Universalis IV`

func main() {
	jogo := flag.String("jogo", "", "pasta de instalação do EU4 (padrão: procurar sozinho)")
	mod := flag.String("mod", "", "pasta do mod instalado (padrão: procurar nos Documentos)")
	x := flag.Int("x", -1, "posição horizontal do texto (padrão: perguntar, sugestão 30)")
	y := flag.Int("y", -1, "posição vertical do texto (padrão: perguntar, sugestão 440)")
	semPausa := flag.Bool("sem-pausa", false, "não esperar Enter no final")
	flag.Parse()

	fmt.Println("=== População Híbrida: instalador do painel da província ===")
	fmt.Println()
	entrada := bufio.NewReader(os.Stdin)
	err := executar(entrada, *jogo, *mod, *x, *y)
	codigo := 0
	if err != nil {
		fmt.Println()
		fmt.Println("ERRO:", err)
		codigo = 1
	}
	if !*semPausa {
		fmt.Print("\nPressione Enter para fechar...")
		entrada.ReadString('\n')
	}
	os.Exit(codigo)
}

func executar(entrada *bufio.Reader, jogo, mod string, x, y int) error {
	if mod == "" {
		mod = acharMod()
	}
	if !existe(filepath.Join(mod, "descriptor.mod")) {
		return errors.New("não achei o mod instalado. Instale primeiro com o INSTALAR.bat e rode este programa de novo")
	}
	fmt.Println("Mod encontrado em:", mod)

	if jogo == "" {
		jogo = acharJogo()
	}
	if !ehPastaDoJogo(jogo) {
		fmt.Println("Não achei a pasta do EU4 sozinho.")
		fmt.Println("Arraste a pasta do jogo para esta janela (ou cole o caminho) e aperte Enter:")
		linha, _ := entrada.ReadString('\n')
		jogo = strings.Trim(strings.TrimSpace(linha), `"`)
		if !ehPastaDoJogo(jogo) {
			return fmt.Errorf("a pasta %q não tem interface\\provinceview.gui; ela precisa ser a pasta onde fica o eu4.exe", jogo)
		}
	}
	fmt.Println("Jogo encontrado em:", jogo)
	fmt.Println()

	if x < 0 {
		x = perguntarNumero(entrada, "Posição horizontal do texto (Enter = 30): ", 30)
	}
	if y < 0 {
		y = perguntarNumero(entrada, "Posição vertical do texto (Enter = 440): ", 440)
	}

	original, err := os.ReadFile(filepath.Join(jogo, "interface", "provinceview.gui"))
	if err != nil {
		return err
	}
	novo, err := inserir(original, montarBloco(moldeGui, x, y))
	if err != nil {
		return err
	}
	destinoGui := filepath.Join(mod, "interface", "provinceview.gui")
	destinoDef := filepath.Join(mod, "common", "custom_gui", "pop_painel_provincia.txt")
	if err := gravar(destinoGui, novo); err != nil {
		return err
	}
	if err := gravar(destinoDef, moldeCustomGui); err != nil {
		return err
	}

	fmt.Println()
	fmt.Println("Pronto! Arquivos gravados:")
	fmt.Println("  ", destinoGui)
	fmt.Println("  ", destinoDef)
	fmt.Println()
	fmt.Println("Abra o jogo e clique numa província para ver a linha de população.")
	fmt.Println("Se o texto ficar em cima de outra coisa, rode este programa de novo e")
	fmt.Println("escolha outra posição (por exemplo 40 e 400).")
	fmt.Println("Rode de novo também depois de cada atualização do EU4 e sempre que")
	fmt.Println("reinstalar o mod com o INSTALAR.bat.")
	return nil
}

// acharMod procura o mod instalado em cada pasta Documentos possível (com
// OneDrive ela muda de lugar).
func acharMod() string {
	for _, docs := range pastasDocumentos() {
		p := filepath.Join(docs, filepath.FromSlash(pastaEU4NosDocumentos), "mod", "populacao_hibrida")
		if existe(filepath.Join(p, "descriptor.mod")) {
			return p
		}
	}
	return ""
}

// acharJogo testa as bibliotecas do Steam e os lugares comuns do GOG.
func acharJogo() string {
	var candidatos []string
	for _, steam := range pastasSteam() {
		bibliotecas := []string{steam}
		if vdf, err := os.ReadFile(filepath.Join(steam, "steamapps", "libraryfolders.vdf")); err == nil {
			bibliotecas = append(bibliotecas, bibliotecasSteam(string(vdf))...)
		}
		for _, b := range bibliotecas {
			candidatos = append(candidatos, filepath.Join(b, "steamapps", "common", "Europa Universalis IV"))
		}
	}
	candidatos = append(candidatos, pastasFixasDoJogo()...)
	for _, c := range candidatos {
		if ehPastaDoJogo(c) {
			return c
		}
	}
	return ""
}

func ehPastaDoJogo(pasta string) bool {
	return pasta != "" && existe(filepath.Join(pasta, "interface", "provinceview.gui"))
}

func perguntarNumero(entrada *bufio.Reader, pergunta string, padrao int) int {
	for {
		fmt.Print(pergunta)
		linha, err := entrada.ReadString('\n')
		linha = strings.TrimSpace(linha)
		if linha == "" || err != nil {
			return padrao
		}
		if n, errNum := strconv.Atoi(linha); errNum == nil && n >= 0 {
			return n
		}
		fmt.Println("Digite um número inteiro, ou só aperte Enter para usar o padrão.")
	}
}

func gravar(caminho string, dados []byte) error {
	if err := os.MkdirAll(filepath.Dir(caminho), 0o755); err != nil {
		return err
	}
	return os.WriteFile(caminho, dados, 0o644)
}

func existe(caminho string) bool {
	_, err := os.Stat(caminho)
	return err == nil
}
