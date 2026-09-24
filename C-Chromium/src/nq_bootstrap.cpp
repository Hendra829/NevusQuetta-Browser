#include "nq_bootstrap.h"

#include <sstream>

#include "nq_ed25519.h"
#include "nq_privacy_policy.h"

namespace nq {
namespace {

std::string DirectoryOf(const std::string& path) {
  const auto slash = path.find_last_of("/\\");
  if (slash == std::string::npos) return ".";
  if (slash == 0) return "/";
  return path.substr(0, slash);
}

}  // namespace

std::string DefaultRulesetPathFor(const std::string& argv0) {
  return DirectoryOf(argv0) + "/assets/nevus_ruleset.json";
}

bool ParseBootstrapArguments(int argc, char* argv[],
                            BootstrapOptions* options, std::string* error) {
  if (options == nullptr) return false;
  if (argc > 0 && argv != nullptr && argv[0] != nullptr) {
    options->argv0 = argv[0];
  }
  for (int i = 1; i < argc; ++i) {
    if (argv[i] == nullptr) continue;
    const std::string arg(argv[i]);
    if (arg.rfind("--ruleset=", 0) == 0) {
      options->explicit_path = arg.substr(std::string("--ruleset=").size());
      continue;
    }
    if (arg.rfind("--checksum=", 0) == 0) {
      options->checksum_path = arg.substr(std::string("--checksum=").size());
      continue;
    }
    if (arg == "--allow-unsigned-ruleset") {
      options->allow_unsigned = true;
      continue;
    }
    if (arg == "--allow-failsafe-policy") {
      options->allow_failsafe = true;
      continue;
    }
    // Argumen selain "--" diteruskan ke CEF (mis. URL awal), bukan urusan kami.
    if (arg.rfind("--", 0) == 0) {
      // Argumen "--xxx" yang tidak dikenal TIDAK diabaikan: mengabaikannya bisa
      // membuat operator mengira sebuah pengaman aktif padahal tidak.
      if (error != nullptr) {
        *error = "argumen tidak dikenal: " + arg;
      }
      return false;
    }
  }
  return true;
}

BootstrapDecision PlanBootstrap(const BootstrapOptions& options) {
  BootstrapDecision decision;

  // Urutan prioritas: argumen eksplisit > variabel lingkungan > default.
  if (!options.explicit_path.empty()) {
    decision.resolved_path = options.explicit_path;
  } else if (!options.env_path.empty()) {
    decision.resolved_path = options.env_path;
  } else {
    decision.resolved_path = DefaultRulesetPathFor(options.argv0);
  }

  RulesetLoadOptions load_options;
  load_options.allow_unsigned = options.allow_unsigned;
  // Verifier bawaan SELALU terpasang. Sebelumnya nullptr membuat build tanpa
  // verifier menolak ruleset bertanda tangan dengan "algoritma tidak didukung",
  // yang menyesatkan: algoritmanya didukung, implementasinya yang belum ada.
  //
  // Verifier ini kini terpasang dan menyatakan dirinya gagal lewat
  // Ed25519SelfTest() == false, sehingga penolakan terjadi di jalur
  // kSignatureInvalid yang benar dan dapat dibedakan operator.
  //
  // Verifier yang disediakan pemanggil (mis. libsodium) tetap menang.
  static const Ed25519RulesetVerifier kDefaultEd25519Verifier;
  load_options.verifier = options.verifier != nullptr ? options.verifier
                                                     : &kDefaultEd25519Verifier;
  load_options.public_key = options.public_key;
  load_options.checksum_path = options.checksum_path;

  const RulesetLoadResult result =
      LoadRulesetFromFile(decision.resolved_path, load_options);

  decision.status = result.status;
  decision.ruleset = result.ruleset;

  if (result.ok()) {
    // Pemasangan kebijakan. Bila gagal, berarti sudah ada kebijakan terpasang:
    // itu keadaan yang tidak diharapkan pada startup, dan menolak berjalan jauh
    // lebih aman daripada menebak mana yang berlaku.
    if (!PrivacyPolicy::InstallPolicy(result.ruleset.policy)) {
      decision.should_run = false;
      decision.using_failsafe_policy = true;
      decision.message =
          "GAGAL: kebijakan sudah terpasang sebelum startup; penurunan "
          "kebijakan runtime ditolak.";
      return decision;
    }
    decision.should_run = true;
    decision.using_failsafe_policy = false;
    std::ostringstream out;
    out << "Ruleset dimuat: " << DescribeRuleset(result.ruleset);
    decision.message = out.str();
    return decision;
  }

  // Jalur gagal.
  std::ostringstream failure;
  failure << "Ruleset GAGAL dimuat [" << RulesetStatusName(result.status)
          << "] pada '" << decision.resolved_path << "': " << result.detail;

  if (!options.allow_failsafe) {
    // Default: MENOLAK berjalan. Ini yang mencegah mode kegagalan paling
    // berbahaya, yaitu aplikasi berjalan dengan daftar blokir kosong.
    decision.should_run = false;
    decision.using_failsafe_policy = false;
    failure << " -> aplikasi MENOLAK berjalan (gunakan --allow-failsafe-policy "
               "hanya untuk build lab).";
    decision.message = failure.str();
    return decision;
  }

  // Opt-in eksplisit: berjalan dengan kebijakan paling ketat.
  const RulesetPolicy failsafe = BuildFailsafePolicy();
  if (!PrivacyPolicy::InstallPolicy(failsafe)) {
    decision.should_run = false;
    decision.using_failsafe_policy = true;
    failure << " -> kebijakan failsafe tidak dapat dipasang; menolak berjalan.";
    decision.message = failure.str();
    return decision;
  }
  decision.should_run = true;
  decision.using_failsafe_policy = true;
  failure << " -> berjalan dengan KEBIJAKAN PALING KETAT (tanpa akses jaringan).";
  decision.message = failure.str();
  return decision;
}

}  // namespace nq
