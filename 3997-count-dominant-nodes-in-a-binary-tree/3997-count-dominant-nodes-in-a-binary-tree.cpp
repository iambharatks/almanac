/**
 * Definition for a binary tree node.
 * struct TreeNode {
 *     int val;
 *     TreeNode *left;
 *     TreeNode *right;
 *     TreeNode() : val(0), left(nullptr), right(nullptr) {}
 *     TreeNode(int x) : val(x), left(nullptr), right(nullptr) {}
 *     TreeNode(int x, TreeNode *left, TreeNode *right) : val(x), left(left), right(right) {}
 * };
 */
class Solution {
public:
    int rec(TreeNode *root,int &cnt){
        if(!root) return 0;
        int maxV = rec(root->left,cnt);
        maxV = max(maxV, rec(root->right,cnt));
        if(root->val >= maxV){
            cnt++;
        }
        return max(maxV,root->val);
    }
    int countDominantNodes(TreeNode* root) {
        int cnt = 0;
        rec(root,cnt);
        return cnt;
    }
};