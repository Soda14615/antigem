package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.github.GitHubManager
import ai.antigem.data.github.GitHubManager.Login
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll

/**
 * Settings → GitHub: installs the GitHub CLI, signs in with it, makes git use that login, and lists the account's
 * repositories (clone one to open it as a project). In server mode all of it is the server's gh and git.
 */
@Composable
fun GitHubScreen(cardBg: Color, cardBorder: BorderStroke, onOpenProject: (ProjectItem) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        GitHubManager.init(context)
        GitHubManager.refresh()
    }
    val state by GitHubManager.state.collectAsState()
    var cloneUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn && !state.reposLoaded && !state.reposLoading) GitHubManager.loadRepos()
    }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GitHubStatusCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = GitHubManager::clearMessage) { Icon(Icons.Default.Close, stringResource(R.string.browser_screen_dismiss), Modifier.size(18.dp)) }
                }
            }
        }

        when {
            state.ghInstalled == null -> Hint(if (GitHubManager.remote) stringResource(R.string.git_hub_screen_checking_the_server_for_the_github_cli) else stringResource(R.string.git_hub_screen_checking_the_terminal_for_the_github_cli))
            state.ghInstalled == false || state.installing -> InstallCard(state, cardBg, cardBorder)
            !state.signedIn -> SignInCard(state, cardBg, cardBorder)
            else -> {
                AccountCard(state, cardBg, cardBorder)
                GitSetupCard(state, cardBg, cardBorder)
                ReposCard(state, cardBg, cardBorder, onClone = { cloneUrl = it })
            }
        }
    }

    cloneUrl?.let { url ->
        ai.antigem.ui.components.GitCloneDialog(
            initialUrl = url,
            onCloned = { project ->
                cloneUrl = null
                onOpenProject(project)
            },
            onDismiss = { cloneUrl = null }
        )
    }
}

@Composable
private fun GitHubStatusCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val account = state.account
    val onSurfaceDim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (account?.avatarUrl != null) {
                AsyncImage(
                    model = account.avatarUrl, contentDescription = stringResource(R.string.settings_github_avatar, account.login),
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                )
            } else {
                val (icon, tint) = when {
                    state.ghInstalled == false -> Icons.Outlined.Download to Amber
                    state.signedIn -> Icons.Outlined.AccountCircle to QuotaGreen
                    else -> Icons.Outlined.AccountTree to onSurfaceDim
                }
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val title = when {
                    account != null -> account.name ?: account.login
                    state.signedIn -> "Signed in"
                    state.ghInstalled == false -> "GitHub CLI not installed"
                    state.ghInstalled == true -> "Not signed in"
                    else -> "GitHub"
                }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    account != null -> "@${account.login}"
                    state.signedIn -> state.accountError ?: "Loading account…"
                    state.ghInstalled == false -> if (GitHubManager.remote) "Install gh on the server to continue" else "Install it below to get started"
                    state.ghInstalled == true -> "Sign in below"
                    else -> "Checking…"
                }
                Text(subtitle, fontSize = 12.sp, color = onSurfaceDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (GitHubManager.remote) Pill("SERVER", MaterialTheme.colorScheme.primary)
                    account?.plan?.let { Pill(it.uppercase(), QuotaGreen) }
                }
            }
            if (state.refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { GitHubManager.refresh() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.agent_sheet_ui_refresh)) }
        }
    }
}

@Composable
private fun InstallCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    StepCard(1, stringResource(R.string.git_hub_screen_install_github_cli), null, cardBg, cardBorder) {
        if (GitHubManager.remote) {
            Hint(stringResource(R.string.git_hub_screen_the_github_cli_gh_isn_t_installed))
            Mono("sudo apt install gh        # Debian / Ubuntu\nsudo dnf install gh        # Fedora\nbrew install gh            # macOS / Homebrew")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openUrl(context, GitHubManager.INSTALL_DOCS_URL) }) { Text(stringResource(R.string.git_hub_screen_install_guide)) }
                Button(onClick = { GitHubManager.refresh() }) { Text(stringResource(R.string.agent_sheet_ui_refresh)) }
            }
            return@StepCard
        }
        if (!state.installing) {
            Hint(stringResource(R.string.git_hub_screen_gh_isn_t_installed_in_the_terminal))
            Button(onClick = GitHubManager::installGh) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.git_hub_screen_install_gh))
            }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            Hint(state.installLog.lastOrNull { !it.startsWith("$") } ?: stringResource(R.string.claude_login_dialog_starting))
        }
        if (state.installLog.isNotEmpty()) LogBox(state.installLog)
        state.installError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SignInCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    StepCard(1, stringResource(R.string.git_hub_screen_sign_in_to_github), null, cardBg, cardBorder) {
        state.ghVersion?.let { Hint(it) }
        state.authProblem?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        when (val login = state.login) {
            Login.Idle, is Login.Failed -> {
                Hint(stringResource(R.string.git_hub_screen_sign_in_with_your_browser_github_shows))
                if (login is Login.Failed) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
                        Text(login.message, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
                Button(onClick = GitHubManager::startBrowserLogin) {
                    Icon(Icons.Outlined.Login, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.git_hub_screen_sign_in_with_browser))
                }
                TokenLogin()
            }
            Login.Starting -> Busy("Asking GitHub for a sign-in code…") { GitHubManager.cancelLogin() }
            is Login.WaitingForBrowser -> {
                Text(stringResource(R.string.git_hub_screen_your_one_time_code), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                    Text(
                        login.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Hint(stringResource(R.string.git_hub_screen_1_tap_the_button_below_the_code))
                Button(onClick = {
                    clipboard.setText(AnnotatedString(login.code))
                    openUrl(context, login.url)
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.git_hub_screen_copy_code_open_github))
                }
                Busy("Waiting for you to approve in the browser…") { GitHubManager.cancelLogin() }
            }
            Login.Finishing -> Busy("Finishing sign-in and setting up git…", onCancel = null)
        }
    }
}

@Composable
private fun TokenLogin() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) stringResource(R.string.git_hub_screen_hide_token_sign_in) else stringResource(R.string.git_hub_screen_use_a_personal_access_token_instead), fontSize = 12.5.sp)
    }
    if (!open) return
    Hint(stringResource(R.string.git_hub_screen_classic_tokens_need_the_repo_and_read))
    TextButton(onClick = { openUrl(context, GitHubManager.TOKEN_URL) }, contentPadding = PaddingValues(0.dp)) {
        Text(stringResource(R.string.git_hub_screen_create_a_token_on_github), fontSize = 12.5.sp)
    }
    OutlinedTextField(
        value = token, onValueChange = { token = it.trim() }, label = { Text(stringResource(R.string.git_hub_screen_token)) }, singleLine = true,
        placeholder = { Text(stringResource(R.string.git_hub_screen_ghp_or_github_pat)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Button(enabled = token.isNotEmpty(), onClick = { GitHubManager.loginWithToken(token); token = "" }) { Text(stringResource(R.string.git_hub_screen_sign_in_with_token)) }
}

@Composable
private fun Busy(text: String, onCancel: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
        if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.accounts_ui_cancel)) }
    }
}

@Composable
private fun AccountCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val account = state.account
    var confirmLogout by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.claude_settings_screen_account), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill(stringResource(R.string.git_hub_screen_signed_in), QuotaGreen)
            }
            if (account == null) {
                Hint(state.accountError ?: stringResource(R.string.git_hub_screen_loading_account_details))
            } else {
                account.bio?.let { Text(it, fontSize = 12.5.sp) }
                DetailRow(stringResource(R.string.git_hub_screen_username), account.login, mono = true)
                DetailRow(stringResource(R.string.browser_screen_name), account.name)
                DetailRow(stringResource(R.string.profile_detail_dialog_email), account.email ?: stringResource(R.string.agy_cloud_ui_private))
                DetailRow(stringResource(R.string.git_hub_screen_company), account.company)
                DetailRow(stringResource(R.string.file_details_dialog_location), account.location)
                DetailRow(stringResource(R.string.git_hub_screen_website), account.blog)
                DetailRow(stringResource(R.string.git_hub_screen_repositories), "${account.publicRepos} public" + (account.privateRepos?.let { " · $it private" } ?: ""))
                DetailRow(stringResource(R.string.git_hub_screen_followers), stringResource(R.string.git_hub_screen_followers_following, account.followers.toString(), account.following.toString()))
                DetailRow(stringResource(R.string.git_hub_screen_member_since), account.createdAt?.take(10))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            DetailRow(stringResource(R.string.git_hub_screen_token_scopes), state.scopes, mono = true)
            DetailRow(stringResource(R.string.git_hub_screen_token_stored_in), state.tokenSource?.let { if (it.contains('/')) it.substringAfterLast('/') else it }, mono = true)
            DetailRow(stringResource(R.string.git_hub_screen_git_protocol), state.gitProtocol, mono = true)
            DetailRow("GitHub CLI", state.ghVersion?.removePrefix("gh version ")?.substringBefore(' '), mono = true)
            DetailRow(stringResource(R.string.git_hub_screen_runs_on), if (GitHubManager.remote) stringResource(R.string.git_hub_screen_server) else stringResource(R.string.connect_pc_screen_this_phone))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                account?.let { OutlinedButton(onClick = { openUrl(context, it.htmlUrl) }) { Text(stringResource(R.string.git_hub_screen_open_profile)) } }
                OutlinedButton(
                    enabled = state.busy == null,
                    onClick = { confirmLogout = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.accounts_ui_sign_out)) }
            }
            state.busy?.let { Busy(it, onCancel = null) }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.git_hub_screen_sign_out_of_github)) },
            text = { Text("gh forgets this login, so git can no longer push or reach private repositories" +
                (if (GitHubManager.remote) " on the server" else " in the terminal") + " until you sign in again. Cloned projects stay where they are.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; GitHubManager.logout() }) { Text(stringResource(R.string.accounts_ui_sign_out), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String?, mono: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.5.sp, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GitSetupCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val account = state.account
    var name by remember(state.gitUserName, account) { mutableStateOf(state.gitUserName ?: account?.name ?: account?.login.orEmpty()) }
    var email by remember(state.gitUserEmail, account) { mutableStateOf(state.gitUserEmail ?: account?.email ?: account?.noreplyEmail.orEmpty()) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Git", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            if (!state.gitInstalled) {
                Text("git isn't installed" + (if (GitHubManager.remote) " on the server." else ". Run \"pkg install git\" in the terminal."),
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            CheckRow("git uses your GitHub login", state.gitCredentialHelper)
            if (state.gitCredentialHelper) {
                Hint("git clone, pull and push over https://github.com use this account, in the terminal, the IDE and agent commands" +
                    (if (GitHubManager.remote) " on the server." else "."))
            } else {
                Hint(stringResource(R.string.git_hub_screen_without_this_git_asks_for_a_password))
                Button(enabled = state.busy == null, onClick = { GitHubManager.setupGit() }) { Text(stringResource(R.string.git_hub_screen_set_up_git)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Text(stringResource(R.string.git_hub_screen_commit_identity), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Hint(if (state.gitUserName == null || state.gitUserEmail == null) stringResource(R.string.git_hub_screen_not_set_yet_git_won_t_commit)
                else stringResource(R.string.git_hub_screen_new_commits_are_signed_as, state.gitUserName.toString(), state.gitUserEmail.toString()))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("user.name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, label = { Text("user.email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
            )
            if (account != null && account.email == null) Hint(stringResource(R.string.git_hub_screen_your_github_email_is_private_keeps_it, account.noreplyEmail.toString()))
            val changed = name.trim() != state.gitUserName.orEmpty() || email.trim() != state.gitUserEmail.orEmpty()
            Button(enabled = changed && name.isNotBlank() && email.contains('@') && state.busy == null,
                onClick = { GitHubManager.setGitIdentity(name, email) }) { Text(stringResource(R.string.claude_providers_page_save)) }
        }
    }
}

private const val REPO_PAGE = 30

@Composable
private fun ReposCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke, onClone: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(REPO_PAGE) }
    val q = query.trim()
    val filtered = remember(state.repos, q) {
        if (q.isEmpty()) state.repos
        else state.repos.filter { it.fullName.contains(q, true) || it.description?.contains(q, true) == true || it.language?.contains(q, true) == true }
    }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repositories" + if (state.reposLoaded) " (${state.repos.size})" else "", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (state.reposLoading) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = GitHubManager::loadRepos) { Icon(Icons.Outlined.Refresh, stringResource(R.string.git_hub_screen_reload_repositories), Modifier.size(20.dp)) }
            }
            state.reposError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            if (state.repos.isNotEmpty()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it; shown = REPO_PAGE }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.git_clone_dialog_search_repositories)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (state.reposLoaded && !state.reposLoading) {
                Hint(stringResource(R.string.git_hub_screen_no_repositories_yet))
            } else if (state.reposLoading) {
                Hint(stringResource(R.string.git_clone_dialog_loading_your_repositories))
            }
            filtered.take(shown).forEachIndexed { i, repo ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                RepoRow(repo, ownLogin = state.account?.login, onClone = { onClone(repo.cloneUrl) })
            }
            if (filtered.size > shown) {
                TextButton(onClick = { shown += REPO_PAGE }) { Text(stringResource(R.string.git_hub_screen_show_more_left, (filtered.size - shown).toString())) }
            } else if (q.isNotEmpty() && filtered.isEmpty()) {
                Hint(stringResource(R.string.git_clone_dialog_no_repository_matches, q.toString()))
            }
        }
    }
}

@Composable
private fun RepoRow(repo: GitHubManager.Repo, ownLogin: String?, onClone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Box {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (repo.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Book, null, tint = dim, modifier = Modifier.size(15.dp))
                Text(
                    if (repo.owner.equals(ownLogin, true)) repo.name else repo.fullName,
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (repo.isPrivate) Pill(stringResource(R.string.agy_cloud_ui_private), Amber)
                if (repo.isFork) Pill(stringResource(R.string.git_hub_screen_fork), MaterialTheme.colorScheme.primary)
                if (repo.isArchived) Pill(stringResource(R.string.git_hub_screen_archived), dim)
            }
            repo.description?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val meta = listOfNotNull(repo.language, if (repo.stars > 0) "★ ${repo.stars}" else null, repo.pushedAt?.take(10)?.let { "Updated $it" })
            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), fontSize = 11.sp, color = dim)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.git_hub_screen_clone_and_open)) }, leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) },
                onClick = { menu = false; onClone() })
            DropdownMenuItem(text = { Text(stringResource(R.string.git_hub_screen_open_on_github)) }, leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) },
                onClick = { menu = false; openUrl(context, repo.htmlUrl) })
            DropdownMenuItem(text = { Text(stringResource(R.string.git_hub_screen_copy_clone_url)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; clipboard.setText(AnnotatedString(repo.cloneUrl)) })
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
