/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

use anyhow::Result;
use elp_ide_db::elp_base_db::salsa;
use elp_ide_db::elp_base_db::salsa::ParallelDatabase;
use elp_ide_db::elp_base_db::FileId;
use elp_ide_db::elp_base_db::ModuleIndex;
use elp_ide_db::elp_base_db::ModuleName;
use elp_ide_db::elp_base_db::ProjectData;
use elp_ide_db::elp_base_db::ProjectId;
use elp_ide_db::elp_base_db::SourceDatabase;
use elp_ide_db::parse_server::ParseError;
use elp_ide_db::Eqwalizer;
use elp_ide_db::EqwalizerDatabase;
use elp_ide_db::EqwalizerDiagnostics;
use elp_ide_db::ErlAstDatabase;
use elp_ide_db::LineIndex;
use elp_ide_db::LineIndexDatabase;
use elp_ide_db::RootDatabase;
use elp_project_model::AppType;
use std::sync::Arc;

pub use elp_ide_db;
pub use elp_ide_db::parse_server;

pub type Cancellable<T> = Result<T, salsa::Cancelled>;

/// `AnalysisHost` stores the current state of the world.
#[derive(Debug, Default)]
pub struct AnalysisHost {
    db: RootDatabase,
}

impl AnalysisHost {
    /// Returns a snapshot of the current state, which you can query for
    /// semantic information.
    pub fn analysis(&self) -> Analysis {
        Analysis {
            db: self.db.snapshot(),
        }
    }

    pub fn raw_database(&self) -> &RootDatabase {
        &self.db
    }
    pub fn raw_database_mut(&mut self) -> &mut RootDatabase {
        &mut self.db
    }
}

/// Analysis is a snapshot of a world state at a moment in time. It is the main
/// entry point for asking semantic information about the world. When the world
/// state is advanced using `AnalysisHost::apply_change` method, all existing
/// `Analysis` are canceled (most method return `Err(Canceled)`).
#[derive(Debug)]
pub struct Analysis {
    db: salsa::Snapshot<RootDatabase>,
}

// As a general design guideline, `Analysis` API are intended to be independent
// from the language server protocol. That is, when exposing some functionality
// we should think in terms of "what API makes most sense" and not in terms of
// "what types LSP uses". We have at least 2 consumers of the API - LSP and CLI
impl Analysis {
    /// Gets the file's `LineIndex`: data structure to convert between absolute
    /// offsets and line/column representation.
    pub fn line_index(&self, file_id: FileId) -> Cancellable<Arc<LineIndex>> {
        self.with_db(|db| db.file_line_index(file_id))
    }

    /// Computes the set of eqwalizer diagnostics for the given file.
    pub fn eqwalizer_diagnostics(
        &self,
        project_id: ProjectId,
        file_ids: Vec<FileId>,
        format: parse_server::Format,
    ) -> Cancellable<Arc<EqwalizerDiagnostics>> {
        self.with_db(|db| db.eqwalizer_diagnostics(project_id, file_ids, format))
    }

    /// Low-level access to eqwalizer
    pub fn eqwalizer(&self) -> &Eqwalizer {
        self.db.eqwalizer()
    }

    /// ETF for the module's abstract forms
    pub fn module_ast(
        &self,
        file_id: FileId,
        format: parse_server::Format,
    ) -> Cancellable<Result<Arc<Vec<u8>>, Arc<Vec<ParseError>>>> {
        self.with_db(|db| db.module_ast(file_id, format))
    }

    pub fn project_data(&self, file_id: FileId) -> Cancellable<Option<Arc<ProjectData>>> {
        self.with_db(|db| {
            Some(db.project_data(db.app_data(db.file_source_root(file_id))?.project_id))
        })
    }

    /// Returns module name
    pub fn module_name(&self, file_id: FileId) -> Cancellable<Option<ModuleName>> {
        self.with_db(|db| {
            let app_data = db.app_data(db.file_source_root(file_id))?;
            db.module_index(app_data.project_id)
                .module_for_file(file_id)
                .cloned()
        })
    }

    pub fn module_index(&self, project_id: ProjectId) -> Cancellable<Arc<ModuleIndex>> {
        self.with_db(|db| db.module_index(project_id))
    }

    pub fn module_file_id(
        &self,
        project_id: ProjectId,
        module: &str,
    ) -> Cancellable<Option<FileId>> {
        self.with_db(|db| db.module_index(project_id).file_for_module(module))
    }

    /// Returns the app_type for a file
    pub fn file_app_type(&self, file_id: FileId) -> Cancellable<Option<AppType>> {
        self.with_db(|db| db.file_app_type(file_id))
    }

    /// Performs an operation on the database that may be canceled.
    ///
    /// ELP needs to be able to answer semantic questions about the
    /// code while the code is being modified. A common problem is that a
    /// long-running query is being calculated when a new change arrives.
    ///
    /// We can't just apply the change immediately: this will cause the pending
    /// query to see inconsistent state (it will observe an absence of
    /// repeatable read). So what we do is we **cancel** all pending queries
    /// before applying the change.
    ///
    /// Salsa implements cancellation by unwinding with a special value and
    /// catching it on the API boundary.
    fn with_db<F, T>(&self, f: F) -> Cancellable<T>
    where
        F: FnOnce(&RootDatabase) -> T + std::panic::UnwindSafe,
    {
        salsa::Cancelled::catch(|| f(&self.db))
    }
}

impl Clone for Analysis {
    fn clone(&self) -> Self {
        Analysis {
            db: self.db.snapshot(),
        }
    }
}