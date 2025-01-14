/*******************************************************************************
 * UW SPF - The University of Washington Semantic Parsing Framework
 * <p>
 * Copyright (C) 2013 Yoav Artzi
 * <p>
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 ******************************************************************************/
package edu.uw.cs.lil.tiny.learn.weakp.loss;

import edu.uw.cs.lil.tiny.parser.IDerivation;
import edu.uw.cs.utils.composites.Pair;

/**
 * Weighting function as described in Singh-Miller and Collins 2007.
 * 
 * @author Yoav Artzi
 * @param <MR>
 */
public interface IUpdateWeightFunction<MR> {
	
	/**
	 * Calculate weight for a non optimal parse.
	 * 
	 * @param nonOptimalParse
	 * @return
	 */
	double evalNonOptimalParse(
			Pair<Double, ? extends IDerivation<MR>> nonOptimalParse);
	
	/**
	 * Calculate weight for a optimal parse.
	 * 
	 * @return
	 */
	double evalOptimalParse(Pair<Double, ? extends IDerivation<MR>> optimalParse);
	
}
