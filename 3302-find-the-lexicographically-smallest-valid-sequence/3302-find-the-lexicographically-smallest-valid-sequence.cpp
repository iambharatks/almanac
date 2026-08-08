class Solution {
public:
    vector<int> validSequence(string word1, string word2) {
        vector<int> res;
        int n = size(word2);
        vector<int> lastIndex(n+1,-1);
        int j = n-1;
        lastIndex[n] = size(word1);
        for(int i = size(word1)-1; i >= 0 && j >= 0; i--){
            if(word1[i] == word2[j]){
                lastIndex[j] = i;
                j--;
            }
        }
        j = 0;
        bool toggle = true;
        for(int i = 0 ; i < size(word1) && j < n ; i++){
            //pick,
            if(word1[i] == word2[j]){
                res.push_back(i);
                j++;
            }else{
                if(toggle){
                    if(lastIndex[j+1] >= i+1){
                        toggle &= false;
                        res.push_back(i);
                        j++;
                    }
                }
            }
        }   
        return (j == n)?res:vector<int>();     
    }
};